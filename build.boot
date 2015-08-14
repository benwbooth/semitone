(set-env! :dependencies '[[org.clojure/clojure "1.7.0"]
                          [intronic/interval-tree "0.2.7"]
                          [incanter/incanter-core "1.9.0"] ]
          :source-paths #{"src/"})
 
(task-options!
  pom {:project 'semitone
       :version "0.1.0"}
  jar {:main 'semitone.core}
  aot {:all true}
  repl {:init-ns 'semitone.core})

(deftask get-soundfonts
  "Download some soundfonts."
  []
  (fn [continue]
    (fn [event]
     (clojure.java.shell/sh "bash" "-c" "mkdir -p soundfonts && cd soundfonts && wget https://woolyss.com/chipmusic/chipmusic-soundfonts/The_Nes_Soundfont.zip && unzip The_Nes_Soundfont.zip")
     (continue event))))

(deftask build
  "Build my project."
  []
  (comp (pom) (jar) (install)))
