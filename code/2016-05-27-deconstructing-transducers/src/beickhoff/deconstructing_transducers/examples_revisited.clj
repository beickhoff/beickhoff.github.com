(ns beickhoff.deconstructing-transducers.examples-revisited)

(defn taking [n]
  (fn [original-step-fn]
    (let [counter (volatile! 0)]
      (fn [acc x]
        (let [i (vswap! counter inc)]
          (if (<= i n)
            (original-step-fn acc x)
            (reduced acc)))))))
