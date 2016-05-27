(defproject beickhoff.deconstructing-transducers "0.0-SNAPSHOT"
  :url "http://example.com/FIXME"
  :dependencies [[io.netty/netty-buffer "4.0.36.Final"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/core.match "0.3.0-alpha4"]]
  :main ^:skip-aot beickhoff.deconstructing-transducers.examples
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[midje "1.8.3"]]}
             :uberjar {:aot :all}})
