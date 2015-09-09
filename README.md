semitone
============

A clojure music notation DSL for midi programming.

*This Software is ALPHA QUALITY!* Please file bug reports! Thanks!

Getting Started
===============

Make sure you have ```boot-clj``` installed, e.g. ```brew install boot-clj```. Then do:

```
git clone https://github.com/benwbooth/semitone.git
cd semitone
boot repl
```

Now you can start making music!

```
(play `(c d e f g a b c >))
```

How it works
============

```semitone``` is a DSL language written in clojure specifically designed for writing MIDI-based music compositions. It is designed to allow users to compose music in a traditional music notation style, but being a DSL, it is flexible enough to allow any kind of algorithmic music generation as well. ```semitone``` makes use of the built-in Java real time sequencer code for MIDI sequencing and playback. When users call the ```(play)``` function, the DSL language is converted into ```MIDIEvent``` objects, which are put into the Java sequencer's ```Track``` objects.

The ```semitone``` language
===========================

```semitone``` takes inspiration from other music notation languages, such as [MML](https://en.wikipedia.org/wiki/Music_Macro_Language), [OpusModus](http://opusmodus.com/omn.html), and [Zel](http://www.zelsoftware.org/). Here is the structure of a "note" in ```semitone```:

```Length``` ```Note``` ```Parameters```

When composing notes in ```semitone```, the ordering of these three attributes is important. The length must come before the note, and the note must come before the parameters. Any of the three attributes may be omitted, however, in which case the attribute used in the previous note will remain in effect. This concept is  similar to [OpusModus Notation](http://opusmodus.com/omn.html), except that the ```dynamic``` and ```expression``` attributes are combined into one ```parameters``` attribute.

Length
======
 
The ```Length``` argument describes the duration of the note. Note length may be specified using Clojure's ratio literals (e.g. ```1/4``` is a quarter note), or using letters to designate various durations:

``` 
x: double-whole note
w: whole note
h: half note
q: quarter note
i: eighth note
s: sixteenth note
t: thirty-second note
j: sixty-fourth note
l: 128th note
m: 256th note
n: 512th note
o: 1024th note
```

Length letters can be combined together to form the sum of the duration of all length notes, e.g. ```wh``` = ```1/1``` + ```1/2```. If using ratio literals for the length, keep in mind clojure automatically tries to reduce ratio literals into integers when possible. I've included a ```~(ratio numerator denominator)``` function which might help with this, but it's kinda verbose. A length prefixed with a ```-``` indicates a rest note. [Dotted notes](https://en.wikipedia.org/wiki/Dotted_note) are supported. If you add a number to the end of a length, e.g. ```q3```, the note length will be divided by ```3```. This is useful for simulating [tuplets](https://en.wikipedia.org/wiki/Tuplet), although ```semitone``` doesn't adhere to all of the irregularities of traditional music notation regarding tuplets. It simply divides the note length by whatever number you gave.

There are two "repetition" symbols: ```=``` and ```-```. The ```=``` symbol repeats the last note with the same duration. You can extend the duration by repeating the ```=``` symbol: ```===``` repeats the last note with the duration 3x longer. ```-``` is a rest repetition symbol. It rests for the duration of the previous note/rest. You can also extend the ```-``` rest symbol: ```---``` plays a rest for 3x the previous note/rest's duration. The repetition operators may be useful for writing percussion.

Note
====

A "note" in ```semitone``` can basically mean any MIDI message. Note On/Off pairs can be specified using the note name:

```
C
C#
db
f##
gbb
G
```

Notes written in uppercase will play one octave lower than lowercase notes. Accidentals may be given after the note name:

```
# sharp
## double-sharp
b flat
bb double-flat
n natural
```

Notes may also be specified using raw MIDI key values from 0-127:

```
k60
k72
```

[Ties](https://en.wikipedia.org/wiki/Tie_(music)) can be written by appending or prepending a ```_``` character to a note.:

```C#_ _C#```

```C#_``` effectively plays a Note On without a Note Off, and ```_C#``` plays a Note Off without a Note On. Make sure your ties are properly paired, or you may get a note that plays forever!

If a note is prepended with a ```*``` character, it is treated as a Key Pressure message instead of a Note On/Off pair:

``` 
*c   Send key pressure message which the note value C
*k60 Same as above
```

You may also append the octave number to the note:

```c4``` plays middle C.

"Notes" can also be CC messages:

```cc32``` plays a CC 32 message.

or Program changes:

```prog1``` plays a program change message that sets the program to 1.

or channel pressure:

```*32``` sets channel pressure to 32.

or pitch bend:

```|-100``` sets pitch bend to -100 cents from center position.

I tried to find a reasonable syntax for each of the [MIDI Messages](http://www.midi.org/techspecs/midimessages.php) given in the specification.

Parameters
==========

Notes can be adjusted using "Parameter" syntax:

```
c4 !64                 ;; plays middle c with attack set to 64 (range is 0-127)
c4 ?32                 ;; plays middle c with release set to 32 (range is 0-127)
c4 !64?32              ;; plays middle c with attack set to 64 and release set to 32
```

```attack``` refers to the MIDI Note On messages' value parameter, ```release``` is the Note Off's value parameter. There are two other parameters:

```
c4 $128                ;; plays middle c with the Note On's event time *displaced* forward by 128 ticks
c4 &-128                ;; plays middle c with the Note Off's event time *displaced* backward by 128 ticks
```

These are the _displacement_ parameters. ```$``` is ```displacement start```. It adjusts the tick value of the Note On message. This could be useful for humanization. ```&``` is ```displacement end```. It's useful for staccato/legato effects.

Parameter values can be given using integer values, as in the above examples, or using floating point literals. Parameters given with integer literals tend to have the raw MIDI values as their upper and lower bounds, e.g. 0-127. When parameters are given using floating point literals, the range tends to be from ```0.0``` to ```1.0```, with a few exceptions which will be noted. 

Relative parameter adjustments
==============================

All the parameter attributes I've given so far have been specified as absolute values, but you can also specify relative parameters using ```<``` and ```>```:

```
c4 !64 >10 >10 >10 >10
```

The above will play middle c with an attack of 64, then will re-play the same note four more times, with the attack increasing by 10 every time. Here's the opposite, with the attack value decreasing:

```
c4 !64 <10 <10 <10 <10
```

Relative parameter adjustments may also be given by simply repeating the ```<``` and ```>``` symbols:

```
c4 !64 >>> >>> >>> >>>
```

The above plays middle c with attack 64, then plays four more times with the attack increasing by 3 every time.

Free parameter values and envelopes
===================================

One interesting thing about ```semitone``` is that just about every MIDI message is _parameterizable_. For example, you can set the value of CC32 to 64 like this:

```
cc32 64
```

But you can also do this:

```
cc32 64 70 74 80 84 90 94 100
```

or this:

```
cc32 0.0 0.1 0.2 0.3 0.4 0.5
```

You can also use the ```~(env num-points values)``` function to create envelopes:

```
cc32 ~@(env 10 [0.0 0.5 -0.5 0.5]) 
```

```env``` uses cubic interpolation to create 10 cc values using the values given as guides. 

Almost all notes can be parameterized in this way:

- Note letters A-G expose the _octave_ parameter
- key pressure messages expose the _key pressure_ parameter
- cc messages expose the _cc value_ parameter
- channel pressure messages expose the _channel pressure_ parameter
- pitch bend messages expose the _pitch bend_ parameter
- raw MIDI key messages (e.g. k60) expose the _midi key_ parameter
- explicit attack parameters (e.g. !64) expose the _attack_ parameter
- explicit release parameters (e.g. ?64) expose the _release_ parameter
- explicit displacement-start parameters (e.g. $100) expose the _displacement-start_ parameter
- explicit displacement-end parameters (e.g. &100) expose the _displacement-end_ parameter

The idea being that, once a parameter is exposed, you can then give integer or decimal literals to tweak the parameters until the next note symbol is encountered. You can then also use the ```env``` function to easily write expressive envelopes.

Sequencing
==========

Now that we (hopefully!) have a basic idea of how to write individual notes, how do we sequence them together to compose music?

In ```semitone``` notation, any notes surrounded by parentheses ```()``` play sequentially:

```
(c d e f g)
```

The parentheses notation can also be used to simulate measure bars:

```
(c d e f) (g a b c >)
```

Any notes surrounded by _square brackets_ ```[]``` play *simultaneously*:

```
[c e g]
```

You can use the parentheses to create a sequence of notes, and you can use the square brackets to write both chords and separate voices. 

The duration of a ```semitone``` expression in parentheses is the sum of the durations of its contents. The duration of an expression in _square brackets_ is equal to the duration of the *last* element inside the square brackets.

Keywords
========

In ```semitone``` notation, clojure keyword literals (e.g. :key-signature) followed by a value can be used to adjust all the stateful parameters that are used to translate notation into MIDI events:

```
:sequencer sequencer                  ;; set the sequencer object we're writing to
:displacement-start value             ;; set the current default displacement-start value in ticks
:displacement-end value               ;; set the current default displacement-end value in ticks
:attack value                         ;; set the current default attack value (0-127)
:release value                        ;; set the current default release value (0-127)
:channel-pressure pressure            ;; set the current default channel pressure value (0-127)
:pitch-bend value                     ;; set the current default pitch bend value (-8192 to 8191)
:key-pressure value                   ;; set the current default key pressure amount (0-127)
:position pos                         ;; set the current tick position we're writing to (0-...)
:tie                                  ;; state for keeping track of note ties
:key number                           ;; set the current default MIDI key value (0-127)
:message-type type                    ;; set the current default MIDI message type (ShortMessage/NOTE_ON, etc.)
:ch channel                           ;; set the MIDI channel we're writing to (0-15)
:track track                          ;; set the MIDI track we're writing to (0-...)
:key-signature {}                     ;; define a map of note name to semitone offsets to describe a key signature
:time-signature [4 4]                 ;; define a two-element vector describing the time signature (currently unused)
:transpose semitone                   ;; transpose the MIDI note messages
:octave number                        ;; set the current default octave number to use when no octave is given
:note-octave                          ;; state for keeping track of note octaves
:param param-keyword                  ;; set the current default tweakable parameter keyword
:cc-value value                       ;; set the current default CC value
:tempo bpm                            ;; set the current tempo in BPM
:length                               ;; set the current default note length in ticks
:repeat-length                        ;; set teh current default repeat length in ticks
:midi-message                         ;; internal state
:notes                                ;; sets the semitone expression being evaluated
```

You shouldn't need to use most of these keywords, but they can be useful for certain things, like setting the key signature and the tempo.

Sequencer objects and utility functions
=======================================

There are a few special variables defined in the ```semitone``` namespace:

```*seq*``` is the default MIDI sequence object
```*synth``` is the default MIDI synthesizer object
```*sequencer*``` is the default MIDI sequencer object

There are also a few functions that are useful:

```(ratio value)``` converts the value to a ratio object
```(env num-points values)``` produces an envelope with num-points points using values as a guide
```(load-soundfont soundfont synth)``` loads a synth with the given soundfont file
```(make-sequencer sequence synth)``` creates a new sequencer object

```(compose notes sequencer)``` writes the ```semitone``` notation to the sequencer
```(play notes sequencer)``` writes the ```semitone``` notation to the sequencer and plays the sequencer
```(clear sequencer)``` clears all the MIDI events from the sequencer

I also wrote a print-method for the ```*seq*``` object so you can see the raw MIDI events in a MIDI sequence for debugging purposes.

Play
====

Let's now look back at the first simple example from the beginning of this document:

```
(play `(c d e f g a b c >))
```

We're calling the ```(play)``` function, which composes the ```semitone``` notation into a series of MIDI events, and then plays them through the default synthesizer. Notice that the ```semitone``` notation is always written using syntax quoting. I highly encourage you to write ```semitone``` notation using syntax quotes, because then you can use unquoting to insert the output of functions directly into your notation. This is how the ```env``` enveloping function works. It uses the syntax unquote operator ~@ to put the envelope parameters into the notation.

The ```>``` after the last c note increases the octave of the *previous* note using the octave parameter exposed by the ```c``` note and doing a relative increment of +1. This looks suspiciously similar to the ```>``` operator in MML language, but remember in ```semitone``` notation, the ```>``` operator adjust the *previous* note! Also, it is much more flexible because the ```>``` operator in ```semitone``` can adjust *any* parameter value, not just octaves! Everything can be tweaked.

Big Caveat
==========

Since this whole thing is implemented on top of Java's Sequencer APIs, it suffers from a limitation: external MIDI sync is completely unimplemented. That means you can't use this with any external sequencer (such as reaper) at the moment. I'm hoping to remedy this by patching the Java sequencer API to add external MIDI clock and MTC master/slave support. I've gotten started on it, but I don't know when it'll be finished. When it is though, you should be able to sync ```semitone``` with external sequencers and send and receive MIDI messages between them. 

Sorry for crappy examples
=========================

I'm a programmer not a composer, and I haven't had time to produce more tests/examples. Sorry about that! Patches welcome! :)

Buggy As Hell, Badly Written, etc.
==================================

I know there are still LOTS of bugs in the code. Also, the code quality is pretty bad. I used regexes for parsing to keep things simple, but I'd like to convert it to use parser combinators at some point.

What's next
===========

- Finish writing Java sequencer that can do external sync
- [MPE support](http://expressiveness.org/2015/04/24/midi-specifications-for-multidimensional-polyphonic-expression-mpe)
- implement time signatures
- add shortcuts for intervals, chords, key signatures, dynamics
- convenient meta message syntax
- convenient sysex syntax
- SMPTE time?
