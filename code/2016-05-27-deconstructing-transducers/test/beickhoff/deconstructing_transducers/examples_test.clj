(ns beickhoff.deconstructing-transducers.examples-test
  (:require [beickhoff.deconstructing-transducers.examples :refer :all]
            [midje.sweet :refer :all]))

(defn- max-number [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (< a b) b
    :else a))

(defn- example-lazy-sequence [n]
  (when (pos? n)
    (lazy-seq
      (cons n (example-lazy-sequence (dec n))))))

(fact "we can incrementally-process many example tasks"
  (calculate-the-sum-of-a-collection-of-numbers [1 3 5 7]) => 16

  (find-the-maximum-element-in-a-collection max-number [1 5 3]) => 5

  (transfer-elements-from-one-collection-to-another [1 1 2 3] #{}) => #{1 3 2}

  (with-out-str (print-the-contents-of-a-collection-to-standard-output [1 1 2 3])) =>
"1
1
2
3
"
  (find-whether-a-given-element-occurs-in-the-sequence 5 (range 3)) => false
  (find-whether-a-given-element-occurs-in-the-sequence 5 (range 6)) => true

  (realized? (example-lazy-sequence 5)) => false
  (realized? (realize-the-sequence (example-lazy-sequence 3))) => true

  (transfer-the-first-n-elements-of-a-sequence-into-a-vector 10 (range)) => [0 1 2 3 4 5 6 7 8 9])
