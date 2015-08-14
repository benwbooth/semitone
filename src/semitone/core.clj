#!/usr/bin/env boot

;; TODO:
;;   MPE support
;;    - :channel can be a list of available channels, in order of preference
;;    - get start/stop of note to be played
;;    - get all the notes that intersect the note to be played on the current track
;;      - do a linear scan of the track
;;    - get a unique set of channels from the intersecting notes
;;    - choose to play on the next available channel on the channel list
;;    - if no channels are available, play on the next channel/least used channel
;;   DAW recording interface for capturing param values / performances
;;   :time-signature [4 4]
;;   add shortcuts for intervals, chords, key signatures, dynamics
;;   convenient meta message functions
;;   convenient sysex function
;;   SMPTE time

;; Useful links:
;; http://www.midi.org/techspecs/midimessages.php
;; http://computermusicresource.com/midikeys.html
;; http://www.recordingblogs.com/sa/tabid/88/Default.aspx?topic=MIDI+meta+messages

(ns semitone.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.lang.Numbers :refer [toRatio]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [incanter.interpolation :refer [interpolate-parametric]])
  (:import [javax.sound.midi
            MidiSystem MidiMessage MetaMessage MetaEventListener
            SysexMessage ShortMessage Sequencer Sequence MidiEvent Track]
           [java.nio ByteBuffer]
           [com.sun.media.sound SF2Soundbank]))

(defn ratio [value]
  (toRatio value))

(defn env [num-points values & args]
  (let [args (if (empty? args) '(:cubic) args)
        interp (apply interpolate-parametric values args)
        fix-type (cond
                   (every? ratio? values) toRatio
                   (every? integer? values) int
                   :else double)]
    (map #(fix-type (interp %)) (range 0 1 (/ 1.0 num-points)))))

(defn vec-rest [coll]
  (if (vector? coll)
    (vec (clojure.core/rest coll))
    (clojure.core/rest coll)))

(defn parse-state [notes state]
  (if (empty? notes)
    state
    (if (map? (first notes))
      (do
        (when (contains? (first notes) :tempo)
          (let [track (aget (.getTracks (.getSequence (:sequencer state))) (:track state))]
            (.add track
                  (MidiEvent.
                   (MetaMessage. 0x51
                                 (.array
                                  (.put (ByteBuffer/allocate 3)
                                        (.array (.putInt
                                                 (ByteBuffer/allocate 4)
                                                 (int (* (/ 1 (get (first notes) :tempo)) 60 1e6))))
                                        1 3))
                                 3)
                   (:position state))))
          (merge state (first notes) {:notes (vec-rest notes)})))
      state)))

(defn parse-length [notes state]
  (if (or (empty? notes)
          (not (or (ratio? (first notes))
                   (symbol? (first notes)))))
    state
    (let [length (first notes)
          ppq (.getResolution (.getSequence (:sequencer state)))]
      (cond
        (ratio? length)
        (merge state {:message-type (if (< length 0) nil (:message-type state))
                      :length (Math/abs (* 4.0 length ppq))
                      :repeat-length nil
                      :notes (vec-rest notes)})
        (symbol? length)
        (if-let [m (re-matches #"(-|)((?:[xwhqistjlmno]\.*)+)" (name length))]
          (let [length-map {"x" 2 "w" 1 "h" 1/2 "q" 1/4 "i" 1/8 "s" 1/16 "t" 1/32
                            "j" 1/64 "l" 1/128 "m" 1/256 "n" 1/512 "o" 1/1024}]
            (merge state {:message-type (if (= "-" (get m 1)) nil (:message-type state))
                          :length (reduce +
                                          (map #(* 4.0
                                                   (get length-map (get % 1))
                                                   (- 2.0 (/ 1.0 (reduce * (repeat (count (get % 2)) 2))))
                                                   ppq)
                                               (re-seq #"\G([xwhqistjlmno])(\.*)" (get m 2))))
                          :repeat-length nil
                          :notes (vec-rest notes)}))
          (if-let [m (re-matches #"([-=])[-=]*" (name length))]
            (do 
              (merge state {:repeat-length (* (:length state) (count (name length)))
                            :message-type (if (= "=" (get m 1)) (:message-type state) nil)
                            :notes (vec-rest notes)}))
            state))
        :else state))))

;; handles note on/off, key pressure, cc, program change, channel pressure, pitch bend messages
(defn parse-note [notes state]
  (if (or (empty? notes)
          (not (or (= (type (first notes)) MidiMessage)
                   (symbol? (first notes)))))
    state
    (if (= (type (first notes)) MidiMessage)
      (merge state {:midi-message (first notes) :notes (vec-rest notes)})
      (let [note (clojure.string/replace (name (first notes)) #"__[0-9]+__auto__$" "#")]
        ;; note on/off / key pressure by key name
        (if-let [m (re-matches #"(\*|-|)([a-gA-G])(#|##|b|bb|n|)(-?[0-9]+|)(-|)" note)]
          (let [note-map {"C" -12 "D" -10 "E" -8 "F" -7 "G" -5 "A" -3 "B" -1
                          "c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11}
                accidental-map {"#" 1 "##" 2 "b" -1 "bb" -2 "n" 0 "" nil}
                key-pressure (= "*" (get m 1))
                key (get note-map (get m 2))
                note-octave (if (= "" (get m 4)) nil (Integer. (get m 4)))
                accidental (or (get accidental-map (get m 3))
                               (get (:key-signature state) (clojure.string/lower-case (get m 2)))
                               0)]
            (merge state {:message-type (if key-pressure ShortMessage/POLY_PRESSURE ShortMessage/NOTE_ON)
                          :tie (cond
                                 (= "-" (get m 1)) :end
                                 (= "-" (get m 5)) :begin
                                 :else nil)
                          :key (+ key accidental)
                          :note-octave note-octave
                          :param (if key-pressure :key-pressure :octave)
                          :notes (vec-rest notes)}))
          ;; cc
          (if-let [m (re-matches #"cc([0-9]+)" note)]
            (merge state {:message-type ShortMessage/CONTROL_CHANGE
                          :cc (mod (Integer. (get m 1)) 128)
                          :param :cc-value
                          :notes (vec-rest notes)})
            ;; program change
            (if-let [m (re-matches #"prog([0-9]+)" note)]
              (merge state {:message-type ShortMessage/PROGRAM_CHANGE
                            :prog (mod (Integer. (get m 1)) 128)
                            :notes (vec-rest notes)})
              ;; channel pressure, pitch bend, note on/off / key pressure by key number
              (if-let [m (re-matches #"(\*|\^|\*k|k|-k)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*)(?:[eE]-?[0-9]+)?|)(-|)" note)]
                (let [param ({"*" :channel-pressure "^" :pitch-bend "*k" :key-pressure "k" :key} (get m 1))
                      [min-value max-value double-min double-max]
                      (get {:channel-pressure [0 128 0.0 1.0]
                            :key-pressure [0 128 0.0 1.0]
                            :key [0 128 -1.0 10.0]
                            :pitch-bend [0 16384 -1.0 1.0]} param)
                      old-value (get state param)
                      value (if (re-find #"[.eE]" (get m 3))
                              (max min-value (min max-value
                                                  (int (+
                                                        (* (/ (- (Double. (get m 3)) double-min)
                                                              (- double-max double-min))
                                                           (- max-value min-value))
                                                        min-value))))
                              (+ (mod (- (if (= "" (get m 3))
                                           (if (= "" (get m 2)) 0 1)
                                           (Integer. (get m 3)))
                                         min-value)
                                      (- max-value min-value))
                                 min-value))
                      value (cond
                              (= \> (get (get m 2) 0)) (mod (+ old-value (or value 0) (- (count (get m 2)) 1)) max-value)
                              (= \< (get (get m 2) 0)) (mod (- old-value (or value 0) (- (count (get m 2)) 1)) max-value)
                              (and (= "" (get m 2)) (= "" (get m 3))) old-value
                              :else value)]
                  (merge state
                         (cond
                           (= "^" (get m 1))
                           {:message-type ShortMessage/PITCH_BEND
                            param value
                            :param param}
                           (= "&" (get m 1))
                           {:message-type ShortMessage/CHANNEL_PRESSURE
                            param value
                            :param param}
                           (= "&k" (get m 1))
                           {:message-type ShortMessage/POLY_PRESSURE
                            :key value
                            :param param}
                           (or (= "k" (get m 1)) (= "-k" (get m 1)))
                           {:message-type ShortMessage/NOTE_ON
                            :tie (cond
                                   (= "-k" (get m 1)) :end
                                   (= "-" (get m 5)) :begin
                                   :else nil)
                            param value
                            :param param}
                           :else (throw (Exception. (format "Unhandled message: %s" (get m 1)))))
                         {:notes (vec-rest notes)}))
                state))))))))

(defn parse-param [notes state]
  (if (or (empty? notes)
          (not (or (symbol? (first notes))
                   (and (number? (first notes)) (not (ratio? (first notes)))))))
    state
    (let [token (first notes)
          token (if (number? token) (str token) (name token))]
      (if-let [m (re-matches #"(!|\?|&|\$)" token)]
        (let [param
              (get {"!" :attack
                    "?" :release
                    "&" :displacement-end
                    "$" :displacement-start}
                   (get m 1) (:param state))]
          (recur (vec-rest notes) (merge state {:param param})))
        (if (re-matches #"((!|\?|&|\$|)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*)(?:[eE]-?[0-9]+)?|))+" token)
          (merge
           (apply merge state
                  (mapcat #(if (= "" (get % 0)) []
                               (let [param
                                     (get {"!" :attack
                                           "?" :release
                                           "$" :displacement-start
                                           "&" :displacement-end}
                                          (get % 1) (:param state))
                                     [min-value max-value double-min double-max]
                                     (get {:attack [0 128 0.0 1.0]
                                           :release [0 128 0.0 1.0]
                                           :channel-pressure [0 128 0.0 1.0]
                                           :key-pressure [0 128 0.0 1.0]
                                           :cc-value [0 128 0.0 1.0]
                                           :key [0 128 -1.0 10.0]
                                           :pitch-bend [0 16384 -1.0 1.0]
                                           :octave [-1 9 -1.0 10.0]
                                           :displacement-start
                                           [(- (.getResolution (.getSequence (:sequencer state))))
                                            (.getResolution (.getSequence (:sequencer state)))
                                            -1.0 1.0]
                                           :displacement-end
                                           [(- (.getResolution (.getSequence (:sequencer state))))
                                            (.getResolution (.getSequence (:sequencer state)))
                                            -1.0 1.0]}
                                          param)
                                     old-value (get state param)
                                     value (if (re-find #"[.eE]" (get % 3))
                                             (max min-value (min max-value
                                                                 (int (+
                                                                       (* (/ (- (Double. (get % 3)) double-min)
                                                                             (- double-max double-min))
                                                                          (- max-value min-value))
                                                                       min-value))))
                                             (+ (mod (- (if (= "" (get % 3))
                                                          (if (= "" (get % 2)) 0 1)
                                                          (Integer. (get % 3)))
                                                        min-value)
                                                     (- max-value min-value))
                                                min-value))
                                     value (cond
                                             (= \> (get (get % 2) 0)) (mod (+ old-value (or value 0) (- (count (get % 2)) 1)) max-value)
                                             (= \< (get (get % 2) 0)) (mod (- old-value (or value 0) (- (count (get % 2)) 1)) max-value)
                                             (and (= "" (get % 2)) (= "" (get % 3))) old-value
                                             :else value)]
                                 [{param value}]))
                          (re-seq #"\G(!|\?|&|\$|)(>+|<+|)(-?(?:[0-9]+|[0-9]*\.[0-9]+|[0-9]+\.[0-9]*)(?:[eE]-?[0-9]+)?|)" token)))
           {:notes (vec-rest notes)})
          state)))))

(defn get-channel [state]
  (:ch state))

(def ^:dynamic *seq* (Sequence. Sequence/PPQ 256))
(def ^:dynamic *synth* (MidiSystem/getSynthesizer))

(defn load-soundfont [soundfont & [synth]]
  (let [synth (or synth *synth*)
        soundbank (SF2Soundbank. (java.io.FileInputStream. soundfont))]
    (.loadAllInstruments synth soundbank)))

(defn make-sequencer [& [sequence synth]]
  (let [sequence (or sequence *seq*)
        synth (or synth *synth*)
        sequencer (MidiSystem/getSequencer false)]
    (.addMetaEventListener sequencer
                           (reify MetaEventListener
                             (meta [meta]
                               (when (= (.getType meta) 0x51)
                                 (.setTempoInMPQ sequencer (float (.getInt (.put (ByteBuffer/allocate 4) (.getData meta) 1 3))))))))
    (.setSequence sequencer sequence)
    (.createTrack (.getSequence sequencer))
    (.open sequencer)
    (.open synth)
    (.setReceiver (.getTransmitter sequencer) (.getReceiver synth))
    sequencer))

(def ^:dynamic *sequencer* (make-sequencer *seq* *synth*))

(defn compose [notes & [sequencer state]]
  (let [sequencer (or sequencer *sequencer*)
        notes (if (or (vector? notes) (seq? notes)) notes (list notes))
        state (merge {:sequencer sequencer
                      :displacement-start 0
                      :displacement-end 0
                      :attack 127
                      :release 0
                      :channel-pressure 0
                      :pitch-bend 8192
                      :key-pressure 0
                      :position (.getTickPosition sequencer)
                      :tie nil
                      :key 0
                      :message-type ShortMessage/NOTE_ON
                      :ch 0
                      :track 0
                      :key-signature {}
                      :time-signature [4 4]
                      :transpose 0
                      :octave 4
                      :note-octave nil
                      :param :octave
                      :cc-value 0
                      :tempo 120
                      :length (.getResolution (.getSequence sequencer))
                      :repeat-length nil
                      :midi-message nil}
                     (or state {})
                     {:notes notes})]
    (loop [notes notes state state]
      (if (not (empty? notes))
        (let [position (:position state)
              state (cond
                      (or (seq? (first notes)) (vector? (first notes)))
                      (merge (compose (first notes) sequencer state)
                             {:notes (vec-rest notes)})
                      (map? (first notes))
                      (parse-state notes state)
                      (and (keyword? (first notes))
                           (not (empty? (vec-rest notes))))
                      (parse-state (cons {(first notes) (first (vec-rest notes))} (vec-rest (vec-rest notes))) state)
                      :else
                      (let [notes (:notes state)
                            state (parse-length (:notes state) state)
                            state (if (nil? (:repeat-length state)) (parse-note (:notes state) state) state)
                            state (parse-param (:notes state) state)
                            key (cond
                                  (or (= (:message-type state) ShortMessage/NOTE_ON)
                                      (= (:message-type state) ShortMessage/POLY_PRESSURE))
                                  (mod (+ (:key state)
                                          (:transpose state)
                                          (* 12 (+ 1 (or (:note-octave state) (:octave state)))))
                                       128)
                                  (= (:message-type state) ShortMessage/CHANNEL_PRESSURE)
                                  (:channel-pressure state)
                                  (= (:message-type state) ShortMessage/CONTROL_CHANGE)
                                  (:cc state)
                                  (= (:message-type state) ShortMessage/PROGRAM_CHANGE)
                                  (:prog state)
                                  (= (:message-type state) ShortMessage/PITCH_BEND)
                                  (bit-and (:pitch-bend state) 2r1111111)
                                  :else (throw (Exception. (format "Unhandled message-type: %s" (:message-type state)))))
                            value (get {ShortMessage/NOTE_ON (:attack state)
                                        ShortMessage/POLY_PRESSURE (:key-pressure state)
                                        ShortMessage/CHANNEL_PRESSURE 0
                                        ShortMessage/CONTROL_CHANGE (:cc-value state)
                                        ShortMessage/PROGRAM_CHANGE 0
                                        ShortMessage/PITCH_BEND (min 128 (bit-shift-right (:pitch-bend state) 7))}
                                       (:message-type state) 0)
                            track (aget (.getTracks (.getSequence (:sequencer state))) (:track state))]
                        (when (= (count notes) (count (:notes state)))
                          (throw (Exception. (format "Could not parse note: %s"
                                                     (if (symbol? (first notes))
                                                       (name (first notes))
                                                       (str (first notes)))))))
                        (cond
                          (not (nil? (:midi-message state)))
                          (.add track (MidiEvent. (:midi-message state) (:position state)))
                          (not (nil? (:message-type state)))
                          (do
                            (when (not (and (= (:message-type state) ShortMessage/NOTE_ON) (= (:tie state) :end)))
                              (.add track 
                                    (MidiEvent. (ShortMessage.
                                                 (:message-type state)
                                                 (get-channel state)
                                                 key
                                                 value)
                                                (+ (:position state)
                                                   (:displacement-start state)))))
                            (when (and (= (:message-type state) ShortMessage/NOTE_ON) (not (= (:tie state) :begin)))
                              (.add track (MidiEvent. (ShortMessage.
                                                       ShortMessage/NOTE_OFF
                                                       (get-channel state)
                                                       key
                                                       (:release state))
                                                      (max 
                                                       (+ (:position state)
                                                          (:displacement-start state))
                                                       (+ (:position state)
                                                          (or (:repeat-length state) (:length state))
                                                          (:displacement-end state)))))))
                          :else
                          (throw (Exception. (format "Could not parse key: %s" key))))
                        (merge state {:position
                                      (if (and (vector? (:notes state)) (not (empty? (:notes state))))
                                        (:position state)
                                        (+ (:position state) (or (:repeat-length state) (:length state))))})))
              notes (:notes state)]
          (recur notes (merge state {:repeat-length nil
                                     :note-octave nil
                                     :midi-message nil})))
        state))))

(defn play [& [notes sequencer state]]
  (let [sequencer (or sequencer *sequencer*)
        state (compose (or notes '()) sequencer state)]
    (when (nil? notes)
      (.setTickPosition sequencer 0))
    (.start sequencer)
    (while (.isRunning sequencer) (Thread/sleep 100))))

(defn clear [& [sequencer]]
  (let [sequencer (or sequencer *sequencer*)
        sequence (.getSequence sequencer)]
    (doseq [track (.getTracks sequence)]
      (doseq [e (range (- (.size track) 2) -1 -1)]
        (let [event (.get track e)]
          (.remove track event)))
      (.setTick (.get track 0) 0))
    (.setTickPosition sequencer 0)))

(defmethod print-method Sequence [sequence writer]
  (doseq [t (range (alength (.getTracks sequence)))]
    (let [track (aget (.getTracks sequence) t)]
      (doseq [e (range (.size track))]
        (let [event (.get track e)
              tick (.getTick event)
              message (.getMessage event)]
          (cond
            (= (type message) ShortMessage)
            (print-simple (format "Track %d: tick %d: ShortMessage(channel=%d, command=%s, data1=%d, data2=%d)\n"
                                  t tick
                                  (.getChannel message)
                                  (get {ShortMessage/CHANNEL_PRESSURE "CHANNEL_PRESSURE"
                                        ShortMessage/CONTROL_CHANGE "CONTROL_CHANGE"
                                        ShortMessage/NOTE_OFF "NOTE_OFF"
                                        ShortMessage/NOTE_ON "NOTE_ON"
                                        ShortMessage/PITCH_BEND "PITCH_BEND"
                                        ShortMessage/POLY_PRESSURE "POLY_PRESSURE"
                                        ShortMessage/PROGRAM_CHANGE "PROGRAM_CHANGE"}
                                       (.getCommand message)
                                       (str (.getCommand message)))
                                  (.getData1 message)
                                  (.getData2 message)) writer)
            (= (type message) MetaMessage)
            (print-simple (format "Track %d: tick %d: MetaMessage(type=%x, data=%s)\n"
                                  t tick
                                  (.getType message)
                                  (clojure.string/join "," (.getData message))) writer)
            (= (type message) SysexMessage)
            (print-simple (format "Track %d: tick %d: SysexMessage(data=%s)\n"
                                  t tick
                                  (clojure.string/join "," (.getData message))) writer)
            :else (print-simple (format "Track %d: tick %d: %s()"
                                        t tick (type message)) writer)))))))

(defn run-test []
  (play `(c d e f g a b c >)))
