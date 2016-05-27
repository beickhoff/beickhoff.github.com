(ns beickhoff.deconstructing-transducers.json
  (:import [clojure.lang ITransientMap ITransientVector]
           [io.netty.buffer ByteBuf ByteBufProcessor Unpooled]
           [java.nio.charset Charset StandardCharsets])
  (:gen-class))

; Note that this a toy implementation with many outstanding limitations, including but not limited
; to the following:
;
;   1.  It only parses ASCII characters.
;   2.  It is overly permissive syntactically.
;   3.  It currently cannot recover from a parsing error.
;
; Curious parties may be interested to know that I'd hoped to avoid writing a lexer by hand.  My
; first attempt was to leverage the JSR 353 reference implementation.  The main problem was that the
; JsonParser could not resume midstream.  It's a stateful parser.  This parsing transducer requires
; a stateless lexer.  My second and third attempts were to use weka-dev or jflex.  I think jflex
; would've proved a sufficiently powerful tool for this problem.  However, its arduous learning
; curve was more than I was willing to commit for this experiment.

(set! *warn-on-reflection* true)

(def ^Charset ASCII StandardCharsets/US_ASCII)

(def ^:private number-character-set
  #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \+ \- \. \e \E})

(def ^:private character-escape-translation
  {\" \"
   \\ \\
   \/ \/
   \b \backspace
   \f \formfeed
   \n \newline
   \r \return
   \t \tab})

(defn- unread
  ([^ByteBuf buf]
   (unread buf 1))
  ([^ByteBuf buf n]
   (.readerIndex buf (- (.readerIndex buf) n))))

(defn- prepend-byte-buffer ^ByteBuf [^ByteBuf b0 ^ByteBuf b1]
  (if-not b0
    b1
    (Unpooled/wrappedBuffer #^"[Lio.netty.buffer.ByteBuf;" (into-array ByteBuf [b0 b1]))))

(defn- more? [^ByteBuf buf]
  (pos? (.readableBytes buf)))

(def ^:private ^ByteBufProcessor find-nonnumeric
  (reify
    ByteBufProcessor
    (process [_ b]
      (-> b char number-character-set nil? not))))

(defn- match [^String string token-type value ^ByteBuf buf]
  (let [^bytes pattern (.getBytes string ASCII)
        pattern-length (alength pattern)]
    (if (< (.readableBytes buf) pattern-length)
      {:status :end-of-input}
      (loop [i 0]
        (if-not (< i pattern-length)
          {:status :match, :type token-type, :value value}
          (if-not (= (.readByte buf) (nth pattern i))
            {:status :mismatch}
            (recur (inc i))))))))

(defn- match-string [^ByteBuf buf]
  (loop [builder (StringBuilder.)]
    (if-not (more? buf)
      {:status :end-of-input}
      (let [c0 (char (.readByte buf))]
        (if (= \" c0)
          {:status :match, :type :string, :value (.toString builder)}
          (if-not (= \\ c0)
            (recur (.append builder c0))
            (if-not (more? buf)
              {:status :end-of-input}
              (let [c1 (char (.readByte buf))]
                (if-let [[_ v] (find character-escape-translation c1)]
                  (recur (.append builder v))
                  (if-not (= \u c1)
                    {:status :mismatch}
                    (if-not (<= 4 (.readableBytes buf))
                      {:status :end-of-input}
                      (let [c2 (-> (.readBytes buf 4)
                                   .array
                                   (String. ASCII)
                                   (Integer/parseInt 16)
                                   char)]
                        (recur (.append builder c2))))))))))))))

(defn- use-long-if-integral ^Number [^double d]
  (let [l (long d)]
    (if (= (double l) d)
      l
      d)))

(defn- match-whitespace-or-number [^ByteBuf buf]
  (let [c (char (.readByte buf))]
    (if (Character/isWhitespace c)
      {:status :match, :type :whitespace, :value c}
      (if-not (number-character-set c)
        {:status :mismatch}
        (do
          (unread buf)
          (let [result (.forEachByte buf find-nonnumeric)]
            (if (= -1 result)
              {:status :end-of-input}
              (let [length (- result (.readerIndex buf))
                    n (-> (.readBytes buf length)
                          .array
                          (String. ASCII)
                          Double/parseDouble
                          use-long-if-integral)]
                {:status :match, :type :number, :value n}))))))))

(defn lexer [rf]
  (let [carryover (volatile! nil)]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc ^ByteBuf buf*]
       (let [buf (prepend-byte-buffer @carryover buf*)]
          (vreset! carryover nil)
          (loop [acc acc]
            (if-not (more? buf)
              acc
              (let [mark (.readerIndex buf)
                    match-result
                      (case (char (.readByte buf))
                        \n (match "ull" :null nil buf)
                        \t (match "rue" :boolean true buf)
                        \f (match "alse" :boolean false buf)
                        \" (match-string buf)
                        \[ {:status :match, :type :array-open}
                        \] {:status :match, :type :array-close}
                        \{ {:status :match, :type :object-open}
                        \} {:status :match, :type :object-close}
                        \: {:status :match, :type :colon}
                        \, {:status :match, :type :comma}
                        (do (unread buf) (match-whitespace-or-number buf)))]
                (case (:status match-result)
                  :match
                    (recur (rf acc match-result))
                  :end-of-input
                    (do
                      (.readerIndex buf mark)
                      (vreset! carryover buf)
                      acc)
                  :mismatch
                    (let [length (- (.readerIndex buf) mark)
                          ^bytes unrecognized (byte-array length)]
                      (.getBytes buf mark unrecognized 0 length)
                      (throw (IllegalArgumentException.
                               (str "unrecognized token: <" (String. unrecognized ASCII) ">")))))))))))))

(defmacro matrix-match [coordinate & the-rest]
  (let [rows (into [] (take-while #(not= :where %)) the-rest)
        clauses (nth the-rest (inc (count rows)) nil)
        [r0 & rs] rows
        x-values r0
        y-values (into [] (map first) rs)
        matrix (into [] (map #(subvec % 1)) rs)
        m (count x-values)
        n (count y-values)]
    (assert (->> matrix (map count) (every? #(= m %)))
            (format "expected rows of width %d, but instead got widths %s" m (mapv count matrix)))
    (let [y-subcase-by-x (reduce (fn [acc x-index]
                           (let [x-value (nth x-values x-index)]
                             (assoc acc x-value
                               `(case ~(nth coordinate 1)
                                  ~@(sequence cat
                                      (for [y-index (range n)
                                            :let [clause (-> matrix (get y-index) (get x-index))]]
                                        [(nth y-values y-index)
                                         (get clauses clause clause)]))))))
                           {} (range m))]
    `(case ~(nth coordinate 0)
       ~@(sequence cat y-subcase-by-x)))))

(defn parser [rf]
  (letfn [(unexpected-token [token stack]
            (throw (IllegalArgumentException. (format "unexpected token %s; stack := %s" token @stack))))
          (add-element [[v & stack] x]
            (conj stack (conj! v x)))
          (add-mapping [[k m & stack] v]
            (conj stack (assoc! m k v)))]
    (let [stack (volatile! ())]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc token]
          (if (-> token :type (= :whitespace))
            acc
            (loop [{token-type :type, value :value} token]
              (let [[top & _] @stack
                    state (cond
                            (instance? String top)           :object-value
                            (instance? ITransientMap top)    :object-key
                            (instance? ITransientVector top) :vector-element
                            (nil? top)                       nil)]
                (matrix-match [state token-type]
                  [                        nil  :vector-element  :object-key  :object-value ]
                  [         :null    propagate      add-element            _    add-mapping ]
                  [      :boolean    propagate      add-element            _    add-mapping ]
                  [       :number    propagate      add-element            _    add-mapping ]
                  [       :string    propagate      add-element     push-key    add-mapping ]
                  [        :array    propagate      add-element            _    add-mapping ]
                  [       :object    propagate      add-element            _    add-mapping ]
                  [   :array-open  push-vector      push-vector            _    push-vector ]
                  [  :array-close            _       pop-vector            _              _ ]
                  [  :object-open     push-map         push-map            _       push-map ]
                  [ :object-close            _                _      pop-map              _ ]
                  [        :colon            _                _            _          no-op ]
                  [        :comma            _            no-op        no-op              _ ]
                  :where
                  {propagate   (rf acc value)
                   push-vector (do (vswap! stack conj (transient [])) acc)
                   add-element (do (vswap! stack add-element value) acc)
                   pop-vector  (do (vswap! stack pop) (recur {:type :array, :value (persistent! top)}))
                   push-map    (do (vswap! stack conj (transient {})) acc)
                   push-key    (do (vswap! stack conj value) acc)
                   add-mapping (do (vswap! stack add-mapping value) acc)
                   pop-map     (do (vswap! stack pop) (recur {:type :object, :value (persistent! top)}))
                   no-op       acc
                   _           (unexpected-token token stack)})))))))))
