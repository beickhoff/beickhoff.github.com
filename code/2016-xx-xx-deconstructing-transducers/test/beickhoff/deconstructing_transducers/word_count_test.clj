(ns beickhoff.deconstructing-transducers.word-count-test
  (:require [beickhoff.deconstructing-transducers.word-count :refer :all]
            [clojure.core.async :as a :refer [<! <!! >! go go-loop]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer :all]))

(def rfc684-lines
  (when-let [url (io/resource "rfc684.txt")]
    (with-open [in (io/reader url)]
      (into [] (line-seq in)))))

; The expected term frequencies are given by Doug McIlroy's famous pipeline:
; (http://www.leancrew.com/all-this/2011/12/more-shell-less-egg/)
;
;$ cat resources/rfc684.txt | tr -cs A-Za-z '\n' | tr A-Z a-z | sort | uniq -c | sort -rn | sed 10q
;    180 the
;    113 a
;    103 of
;     93 to
;     66 in
;     65 is
;     59 and
;     51 pcp
;     50 that
;     38 for

(fact "term frequency can be modeled as two phases of incremental processing"
  (transduce
    (comp
      split-words
      (remove string/blank?)
      element-frequencies
      (upon-completion
        (fn [freqs]
          (into []
            (comp
              (take 10)
              (map key))
            (sort-by val descending freqs)))))
    nil
    nil
    rfc684-lines)
  => ["the" "a" "of" "to" "in" "is" "and" "pcp" "that" "for"])
