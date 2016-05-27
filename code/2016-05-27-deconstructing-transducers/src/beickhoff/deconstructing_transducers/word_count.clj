(ns beickhoff.deconstructing-transducers.word-count)

(def split-words
  (mapcat #(-> %
            (.replaceAll "[^A-Za-z]++" " ")
            .toLowerCase
            (.split "\\s++"))))

(defn- inc* [n]
  (inc (or n 0)))

(defn- update! [m k f]
  (let [v (m k)]
    (assoc! m k (f v))))

(defn element-frequencies [rf]
  (let [freqs (volatile! (transient {}))]
    (fn
      ([] (rf))
      ([acc] (rf (persistent! @freqs)))
      ([acc x]
        (vswap! freqs update! x inc*)
        (rf acc x)))))

(defn descending [x y]
  (- (compare x y)))

(defn upon-completion [f]
  (fn [rf]
    (fn
      ([] nil)
      ([acc] (f acc))
      ([acc x] acc))))
