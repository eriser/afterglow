(ns afterglow.effects.dimmer
  "Effects pipeline functions for working with dimmer channels for
  fixtures and heads. Some fixtures have damping functions that slow
  down their dimmer response, so you may not get the kind of
  coordination you would like from dimmer-oscillator cues. The
  recommended best practice is to use the dimmer channels as a master
  dimmer level to allow tweaking the overall brightness of the show,
  or a set of fixtures, probably tied to a fader on your controller,
  and using the lightness attribute of a color cue to create
  time-varying brightness effects."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.params :as params]
            [afterglow.effects.util :as fx-util]
            [afterglow.rhythm :refer [metro-snapshot]]
            [taoensso.timbre.profiling :refer [pspy]]))

(defn- assign-level
  "Assigns a dimmer level to the channel."
  [level channel]
  (assoc channel :value level))

(defn dimmer-channel?
  "Returns true if the supplied channel is a dimmer."
  [c]
  (= (:type c) :dimmer))

(defn- gather-dimmer-channels
  "Finds all channels in the supplied fixture list which are dimmers,
  even if they are inside heads."
  [fixtures]
  (let [heads (channels/extract-heads-with-some-matching-channel fixtures dimmer-channel?)]
    (channels/extract-channels heads dimmer-channel?)))

(defn dimmer-cue
  "Returns an effect which simply assigns a value to all dimmers of
  the supplied fixtures. If htp? is true, use
  highest-takes-precedence (i.e. compare to the previous assignment,
  and let the higher value remain)."
  ([level show fixtures]
   (dimmer-cue level show fixtures true))
  ([level show fixtures htp?]
      (let [dimmers (gather-dimmer-channels fixtures)
         resolved (params/resolve-unless-frame-dynamic level show (metro-snapshot (:metronome show)))
         label (if (satisfies? params/IParam level) "<dynamic>" level)]
     (chan-fx/build-parameterized-channel-cue (str "Dimmers=" label (when htp?) " (HTP)") resolved dimmers htp?))))

;; Deprecated now that you can pass an oscillated parameter to dimmer-cue
(defn dimmer-oscillator
  "*Deprecated* Returns an effect function which drives the dimmer
  channels of the supplied fixtures according to a supplied oscillator
  function and the show metronome. If :htp? is true, use
  highest-takes-precedence (i.e. compare to the previous assignment,
  and let the higher value remain). Unless otherwise specified,
  via :min and :max, ranges from 0 to 255. Returns a fractional value,
  because that can be handled by channels with an associated fine
  channel (commonly pan and tilt), and will be resolved in the process
  of assigning the value to the DMX channels."
  [osc fixtures & {:keys [min max htp?] :or {min 0 max 255 htp? true}}]
  (fx-util/validate-dmx-value min)
  (fx-util/validate-dmx-value max)
  (when-not (< min max)
    (throw (IllegalArgumentException. "min must be less than max")))
  (let [range (long (- max min))
        chans (channels/extract-channels fixtures #(= (:type %) :dimmer))
        f (if htp?
            (fn [show snapshot target previous-assignment]
              (pspy :dimmer-oscillator-htp
                    (let [phase (osc snapshot)
                          new-level (+ min (* range phase))]
                      (clojure.core/max new-level (or previous-assignment 0)))))
            (fn [show snapshot target previous-assignment]
              (pspy :dimmer-oscillator
                    (+ min (* range (osc snapshot))))))]
    (chan-fx/build-simple-channel-cue (str "Dimmer Oscillator " min "-" max (when htp? " (HTP)")) f chans)))
