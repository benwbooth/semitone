(set-env! :dependencies '[[org.clojure/clojure "1.7.0"]]
          :source-paths #{"src/"})
 
(task-options!
  pom {:project 'semitone
       :version "0.1.0"}
  jar {:main 'semitone.core}
  aot {:all true})
