(ns beickhoff.deconstructing-transducers.examples-revisited-test
  (:require [beickhoff.deconstructing-transducers.examples :refer :all]
            [beickhoff.deconstructing-transducers.examples-revisited :refer :all]
            [midje.sweet :refer :all]))

(defn printing
  ([])
  ([acc] acc)
  ([acc x]
    (println x)
    acc))

(fact "we can build `take n` as a context-independent transducer"
  (incrementally-process ((taking 10) conj) [] (range)) => [0 1 2 3 4 5 6 7 8 9]
  (with-out-str (incrementally-process ((taking 4) printing) nil (range))) =>
"0
1
2
3
")
