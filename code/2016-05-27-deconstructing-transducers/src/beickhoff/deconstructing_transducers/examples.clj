(ns beickhoff.deconstructing-transducers.examples)

(defn incrementally-process [step-fn zero-value coll]
  (loop [acc zero-value
         elements coll]
    (if (reduced? acc)
      @acc
      (if-let [[x & xs] (seq elements)]
        (recur (step-fn acc x) xs)
        acc))))

(defn calculate-the-sum-of-a-collection-of-numbers [numbers]
  (incrementally-process + 0 numbers))

(defn find-the-maximum-element-in-a-collection [comparator coll]
  (incrementally-process comparator nil coll))

(defn transfer-elements-from-one-collection-to-another [source destination]
  (incrementally-process conj destination source))

(defn print-the-contents-of-a-collection-to-standard-output [coll]
  (incrementally-process (fn [_ x] (println x)) nil coll))

(defn find-whether-a-given-element-occurs-in-the-sequence [element sequence]
  (incrementally-process (fn [_ x] (= x element)) false sequence))

(defn realize-the-sequence [lazy-sequence]
  (incrementally-process (fn [acc _] acc) lazy-sequence lazy-sequence))

(defn transfer-the-first-n-elements-of-a-sequence-into-a-vector [n sequence]
  (letfn [(step-fn [acc x]
            (if (< (count acc) n)
              (conj acc x)
              (reduced acc)))]
    (incrementally-process step-fn [] sequence)))
