(ns beickhoff.deconstructing-transducers.json-test
  (:require [beickhoff.deconstructing-transducers.json :refer :all]
            [clojure.core.async :as a :refer [<! <!! >! go go-loop]]
            [midje.sweet :refer :all])
  (:import [io.netty.buffer Unpooled]))

(defn- byte-buf<-string [s]
  (Unpooled/wrappedBuffer ^bytes (.getBytes s ASCII)))

(defn- blocks [n string]
  (into []
    (comp (partition-all n)
          (map #(Unpooled/wrappedBuffer ^bytes (byte-array %))))
    (.getBytes string ASCII)))

(defmacro go-collecting
  "Runs the given body inside a (go ...) block while collecting elements off the bound channel.
   The channel is given by a standard [symbol expression] binding.  The channel will be closed
   at the end of the go block.  A vector of the collected elements will be returned."
  [[chan-sym chan-def] & body]
  `(let [~chan-sym ~chan-def
         collector# (atom [])
         receiver# (go-loop []
                     (when-let [~'x (<! ~chan-sym)]
                       (swap! collector# conj ~'x)
                       (recur)))]
     (go
       (try
         ~@body
         (finally (a/close! ~chan-sym))))
     (<!! receiver#)
     @collector#))

(fact "we can parse JSON using the combined lexer and parser transducers"
  (go-collecting [c (a/chan 1 (comp lexer parser))]
    (>! c (byte-buf<-string "{\"success\": true}")))
  => [{"success" true}]

  (go-collecting [c (a/chan 1 (comp lexer parser))]
    (>! c (byte-buf<-string "{\"id\""))
    (>! c (byte-buf<-string ": 123"))
    (>! c (byte-buf<-string "4} {\""))
    (>! c (byte-buf<-string "id\": "))
    (>! c (byte-buf<-string "5678}")))
  => [{"id" 1234} {"id" 5678}])
