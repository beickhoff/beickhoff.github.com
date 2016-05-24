(ns beickhoff.deconstructing-transducers.bowling
  (:require [clojure.core.match :refer [match]]))

; A "scoring span" or simply a "span" is a contiguous set of rolls for calculating score.
; Informally, spans are scoring-centric extensions of frames which -- unlike frames -- can overlap.
;
; The start of a span always coincides with the start of a frame, referred to as the span's
; corresponding frame.  The end of the span may extend into subsequent frames when necessary to
; calculate a score.  A span is "closed" when it contains the minimum number of contiguous rolls to
; calculate a (noncumulative) score for its corresponding frame.  A span is "open" if it has too few
; rolls to be closed.  (This terminology is admittedly unfortunate since "open spans" and
; "open frames" are completely unrelated.)

(defn- vector-uncons [v]
  (when (seq v)
    [(nth v 0) (subvec v 1)]))

(defn- spare? [[a b]]
  (when (and a b)
    (= 10 (+ a b))))

(defn- append-roll [spans roll]
  (mapv #(conj % roll) spans))

(defn- maybe-open-a-new-span [spans]
  (let [last-span (peek spans)]
    (case (count last-span)
      2 (conj spans [])
      1 (if (-> last-span (nth 0) (= 10))
          (conj spans [])
          spans)
      0 spans)))

(defn- process-spans [open-spans]
  (loop [frame-scores [], open-spans open-spans]
    (if (empty? (first open-spans))
      [frame-scores open-spans]
      (let [[first-span remaining-spans] (vector-uncons open-spans)]
        (match [first-span]
          [([10 b c] :seq)]         (recur (conj frame-scores (+ 10 b c)) remaining-spans)
          [([10 & _] :seq)]         [frame-scores open-spans]
          [([a b c] :guard spare?)] (recur (conj frame-scores (+ 10 c)) remaining-spans)
          [([a b] :guard spare?)]   [frame-scores open-spans]
          [([a b] :seq)]            (recur (conj frame-scores (+ a b)) remaining-spans)
          [([a] :seq)]              [frame-scores open-spans])))))

(defn frame-scores [rf]
  (let [open-spans (volatile! [[]])]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc x]
        (let [[frame-scores remaining-open-spans] (-> @open-spans
                                                      (append-roll x)
                                                      maybe-open-a-new-span
                                                      process-spans)]
          (vreset! open-spans remaining-open-spans)
          (reduce rf acc frame-scores))))))

(defn cumulative-score [rf]
  (let [score (volatile! 0)]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc x] (rf acc (vswap! score + x))))))
