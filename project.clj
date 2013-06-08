(defproject com.gfredericks/core.logic "0.8.4-LOGIC-137"
  :description "A logic/relational programming library for Clojure"
  :parent [org.clojure/pom.contrib "0.0.25"]

  :source-paths ["src/main/clojure"
                 ;"clojurescript/src/clj"
                 ;"clojurescript/src/cljs"
                 ]
  :test-paths ["src/test/clojure"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1806"]
                 [org.clojure/tools.macro "0.1.1"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [com.datomic/datomic-free "0.8.3551" :scope "provided"]]

  :plugins [[lein-cljsbuild "0.3.0"]]

  :cljsbuild
  {:builds
   [{:id "simple"
     :source-paths ["src/test/cljs"]
     :compiler {:optimizations :simple
                :pretty-print true
                :static-fns true
                :output-to "tests.js"}}
    {:id "adv"
     :source-paths ["src/test/cljs"]
     :compiler {:optimizations :advanced
                :pretty-print true
                :output-to "tests.js"}}]})
