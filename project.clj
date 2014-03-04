(defproject com.guokr/simbase-clj "0.1.1"

    :description "A clojure client for simbase document similarity server"
    :url "https://github.com/guokr/simbase-clj/"

    :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}

    :dependencies [[org.clojure/clojure "1.5.1"]
                   [com.taoensso/carmine "2.4.6"]]
    :source-paths ["src"]

    :compile-path "target/classes"
    :target-path "target/"
    :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"])
