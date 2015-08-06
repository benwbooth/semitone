#!/usr/bin/env boot

(ns semitone.core)

(import [javax.sound.midi 
         MidiSystem MidiMessage MetaMessage MetaEventListener 
         SysexMessage ShortMessage Sequencer Sequence MidiEvent Track]
        [java.nio ByteBuffer])

(defn parse-state [notes state]
  (if (empty? notes)
    state
    (if (map? (first notes))
      (do
        (when (contains? (first notes) :tempo)
          (let [track (get (.getTracks (.getSequence (:sequencer state))) (:track state))]
            (.add (:track state)
                  (MidiEvent.
                   (MetaMessage. 0x51
                                 (.array (.putInt (ByteBuffer/allocate 3) (int (* (/ 1 (get (first notes) :tempo)) 60 1e6)))) 3)
                   (:position state))))
          (merge state (first notes) {:notes (rest notes)})))
      state)))

;; TODO: displacement syntax < >
(defn parse-length [notes state]
  (if (empty? notes)
    state
    (let [length (first notes)
          ppq (.getResolution (.getSequence (:sequencer state)))]
      (cond
        (= (type length) clojure.lang.Ratio)
        (merge state {:key (if (< length 0) nil (:key state))
                      :length (Math/abs (* 4.0 length ppq))
                      :notes (rest notes)})
        (symbol? length)
        (if-let [m (re-matches #"(-|)((?:[dwhqistxofjk]\.*)+)" (name length))]
          (let [length-map {"d" 2 "w" 1 "h" 1/2 "q" 1/4 "i" 1/8 "s" 1/16 "t" 1/32
                            "x" 1/64 "o" 1/128 "f" 1/256 "j" 1/512 "k" 1/1024}]
            (merge state {:key (if (= "-" (get m 1)) nil (:key state))
                          :length (reduce +
                                          (map #(* 4.0
                                                   (get length-map (get % 1))
                                                   (- 2.0 (/ 1.0 (reduce * (repeat (count (get % 2)) 2))))
                                                   ppq)
                                               (re-seq #"\G([dwhqistxofjk])(\.*)" (get m 2))))
                          :notes (rest notes)}))
          (if-let [m (re-matches #"([-=])[-=]*" (name length))]
            (merge state {:length (* (:length state) (count (name length)))
                          :key (if (= "=" (get m 1)) (:key state) nil)
                          :notes (rest notes)})
            state))
        :else state))))

;; handles note on/off, key pressure, cc, program change, channel pressure, pitch bend messages
(defn parse-note [notes state]
  (if (empty? notes)
    state
    (if (= (type (first notes)) MidiMessage)
      (merge state {:key (first notes) :notes (rest notes)})
      (let [note (clojure.string/replace (name (first notes)) #"__[0-9]+__auto__$" "#")]
        ;; note on/off / key pressure by key name
        (if-let [m (re-matches #"(&|-|)([a-gA-G])(#|##|b|bb|n|)(-|)" note)]
          (let [note-map {"C" -12 "D" -10 "E" -8 "F" -7 "G" -5 "A" -3 "B" -1
                          "c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11}
                accidental-map {"#" 1 "##" 2 "b" -1 "bb" -2 "n" 0 "" nil}
                key-pressure (= "&" (get m 1))
                key (get note-map (get m 2))
                accidental (or (get accidental-map (get m 3))
                               (get (:key-signature state) (clojure.string/lower-case (get m 2)))
                               0)]
            (merge state {:key-type (if key-pressure ShortMessage/POLY_PRESSURE ShortMessage/NOTE_ON)
                          :tie (cond
                                 (= "-" (get m 1)) :end
                                 (= "-" (get m 4)) :begin
                                 :else nil)
                          :key (mod (+ (* (+ 1 (:octave state)) 12) key accidental) 128)
                          :param (if key-pressure :key-pressure :octave)
                          :notes (rest notes)}))
          ;; cc
          (if-let [m (re-matches #"cc([0-9]+)" note)]
            (merge state {:key-type ShortMessage/CONTROL_CHANGE
                          :key (mod (Integer. (get m 1)) 128)
                          :param :cc-value
                          :notes (rest notes)})
            ;; program change
            (if-let [m (re-matches #"prog([0-9]+)" note)]
              (merge state {:key-type ShortMessage/PROGRAM_CHANGE
                            :key (mod (Integer. (get m 1)) 128)
                            :param nil
                            :notes (rest notes)})
              ;; channel pressure, pitch bend, note on/off / key pressure by key number
              (if-let [m (re-matches #"(&&|\^|&k|k|-k)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*|)(?:[eE]-?[0-9]+)?)?(-|)" note)]
                (let [max-value (if (= "^" (get m 1)) 16384 128)
                      value (if (re-find #"[.eE]" (get m 3))
                              (max 0 (min max-value (int (* (Double. (get m 3)) max-value))))
                              (mod (Integer. (get m 3)) max-value))
                      param ({"&&" :channel-pressure "^" :pitch-bend "&k" :key-pressure "k" :key} (get m 1))
                      old-value (get state param)
                      value (cond
                              (= \> (get (get m 2) 0)) (mod (+ old-value (or value 0) (- (count (get m 2)) 1)) max-value)
                              (= \< (get (get m 2) 0)) (mod (- old-value (or value 0) (- (count (get m 2)) 1)) max-value)
                              :else value)]
                  (merge state
                         (cond
                           (= "^" (get m 1))
                           {:key-type ShortMessage/PITCH_BEND
                            :key nil
                            param value
                            :param param}
                           (= "&" (get m 1))
                           {:key-type ShortMessage/CHANNEL_PRESSURE
                            :key nil
                            param value
                            :param param}
                           (= "&k" (get m 1))
                           {:key-type ShortMessage/POLY_PRESSURE
                            :key value
                            :param param}
                           (or (= "k" (get m 1)) (= "-k" (get m 1)))
                           {:key-type ShortMessage/NOTE_ON
                            :tie (cond
                                   (= "-k" (get m 1)) :end
                                   (= "-" (get m 4)) :begin
                                   :else nil)
                            param value
                            :param param}
                           :else (throw (Exception. (format "Unhandled message: %s" (get m 1)))))
                         {:notes (rest notes)}))
                state))))))))

(defn parse-param [notes state]
  (if (empty? notes)
    state
    (let [token (first notes)
          token (if (or (= (type token) Long) (= (type token) Double)) (str token) (name token))]
      (if (re-matches #"((!|\?|&|)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*|)(?:[eE]-?[0-9]+)?)?)+" token)
        (merge
         (apply merge state
                (map #(let [param (get {"!" :attack  "?" :release "&" :key-pressure} (get % 1) (:param state))
                            max-value (if (= param :pitch-bend) 16384 128)
                            value (if (re-find #"[.eE]" (get % 3))
                                    (if (= param :pitch-bend)
                                      (max 0 (min max-value (int (* (/ (+ 1.0 (Double. (get % 3))) 2.0) max-value))))
                                      (max 0 (min max-value (int (* (Double. (get % 3)) max-value)))))
                                    (mod (Integer. (get % 3)) max-value))
                            value (cond
                                    (= \> (get (get % 2) 0)) (mod (+ (param state) (or value 0) (- (count (get % 2)) 1)) max-value)
                                    (= \< (get (get % 2) 0)) (mod (- (param state) (or value 0) (- (count (get % 2)) 1)) max-value)
                                    :else value)]
                        {param value})
                     (re-seq #"\G(!|\?|&|@|)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*|)(?:[eE]-?[0-9]+)?)?" token)))
         {:notes (rest notes)})
        state))))

(defn compose [sequencer notes & [state]]
  (let [notes (if (or (vector? notes) (seq? notes)) notes (seq notes))
        state (merge {:sequencer sequencer
                      :notes notes
                      :displacement 0
                      :attack 64
                      :release 64
                      :channel-pressure 0
                      :pitch-bend 8192
                      :key-pressure 0
                      :position (.getTickPosition sequencer)
                      :tie nil
                      :key 0
                      :key-type ShortMessage/NOTE_ON
                      :channel 0
                      :track 0
                      :key-signature {}
                      :octave 4
                      :param :octave
                      :cc-value 0
                      :tempo 120
                      :length (.getResolution (.getSequence sequencer))}
                     (or state {}))]
    (loop [notes notes state state]
      (if (not (empty? notes))
        (let [state
              (let [position (:position state)
                    state
                    (cond
                      (or (seq? (first notes)) (vector? (first notes)))
                      (merge (compose (first notes) state)
                             {:notes (rest notes)}
                             (if (vector? notes) {:position position} {}))
                      (map? (first notes))
                      (parse-state (first notes) state)
                      :else
                      (let [notes (:notes state)
                            state (parse-length (:notes state) state)
                            state (parse-note (:notes state) state)
                            state (parse-param (:notes state) state)
                            value (get {ShortMessage/NOTE_ON (:attack state)
                                        ShortMessage/CHANNEL_PRESSURE (:channel-pressure state)
                                        ShortMessage/CONTROL_CHANGE (:cc-change state)
                                        ShortMessage/PITCH_BEND (:pitch-bend state)
                                        ShortMessage/POLY_PRESSURE (:key-pressure state)}
                                       (:key-type state) 0)
                            track (get (.getTracks (.getSequence (:sequencer state))) (:track state))]
                        (when (= (count notes) (count (:notes state)))
                          (throw (Exception. (format "Couldn't parse note: %s" (str (first notes))))))
                        (cond
                          (and (not (nil? (:key state))) (= (type (:key state)) MidiMessage))
                          (.add track (MidiEvent. (:key state) (:position state)))
                          (not (nil? (:key state)))
                          (do
                            (when (not (and (= (:key-type state) ShortMessage/NOTE_ON) (= (:tie state) :end)))
                              (.add track 
                                    (MidiEvent. (ShortMessage.
                                                 (:key-type state)
                                                 (:channel state)
                                                 (:key state)
                                                 value)
                                                (:position state))))
                            (when (and (= (:key-type state) ShortMessage/NOTE_ON) (not (= (:tie state) :begin)))
                              (.add track (MidiEvent. (ShortMessage.
                                                       ShortMessage/NOTE_OFF
                                                       (:channel state)
                                                       (:key state)
                                                       (:release state))
                                                      (+ (:position state) (:length state) (:displacement state))))))
                          :else nil)
                        state))]
                (merge state {:position (if (vector? notes)
                                          (:position state)
                                          (+ (:position state) (:length state)))}))
              notes (:notes state)]
          (recur notes state))
        state))))

(defn play [sequencer notes & [state]]
  (compose sequencer notes state)
  ;; wait for sequencer to finish playing
  (.start sequencer)
  (while (.isRunning sequencer) (Thread/sleep 250)))

(defn -main [& args]
  (println "Try to play sequence")

  ;; initialize the sequencer
  (def sequencer (MidiSystem/getSequencer))
  (.addMetaEventListener sequencer
                        (reify MetaEventListener
                          (meta [meta]
                            (.setTempoInMPQ sequencer (float (.getInt (.put (ByteBuffer/allocate 3) (.getData meta))))))))
  (.setSequence sequencer (Sequence. Sequence/PPQ 256))
  (.createTrack (.getSequence sequencer))
  (.open sequencer)

  ;; initialize the synth and connect to sequencer
  (def synth (MidiSystem/getSynthesizer))
  (.open synth)
  (.setReceiver (.getTransmitter sequencer) (.getReceiver synth))

  ;; set sequncer to loop continuously
  ;; (.setLoopStartPoint sequencer 0)
  ;; (.setLoopEndPoint sequencer -1)
  ;; (.setLoopCount sequencer Sequencer/LOOP_CONTINUOUSLY)

  ;; add some music to the sequencer and start
  (play sequencer `(c d e f g a b)))

