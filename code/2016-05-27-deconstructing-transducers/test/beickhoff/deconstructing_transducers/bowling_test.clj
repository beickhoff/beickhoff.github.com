(ns beickhoff.deconstructing-transducers.bowling-test
  (:require [beickhoff.deconstructing-transducers.bowling :refer :all]
            [midje.sweet :refer :all]))

(defn- printing
  ([])
  ([acc] acc)
  ([acc x]
    (println x)
    acc))

(fact "we can propagate cumulative scores using the bowling transducers"
  (with-out-str (transduce (comp frame-scores cumulative-score) printing nil
                           [10, 0 10, 2 3, 7 1, 9 1, 6 3, 0 0, 9 0, 10, 10 10 3])) =>
"20
32
37
45
61
70
70
79
109
132
"
  (with-out-str (transduce (comp frame-scores cumulative-score) printing nil
                           [5 5, 5 5, 5 5, 5 5, 5 5, 5 5, 5 5, 5 5, 5 5, 5 5 5])) =>
"15
30
45
60
75
90
105
120
135
150
"
  (with-out-str (transduce (comp frame-scores cumulative-score) printing nil
                           [10, 10, 10, 10, 10, 10, 10, 10, 10, 10 10 10])) =>
"30
60
90
120
150
180
210
240
270
300
")
