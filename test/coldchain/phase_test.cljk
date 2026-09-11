(ns coldchain.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [coldchain.phase :as phase]))

(deftest valid-phase-membership
  (is (true? (phase/valid-phase? :inbound)))
  (is (true? (phase/valid-phase? :archived)))
  (is (false? (phase/valid-phase? :not-a-phase))))

(deftest can-transition-forward-only
  (testing "forward transitions are valid"
    (is (true? (phase/can-transition? :inbound :storage)))
    (is (true? (phase/can-transition? :inbound :archived))))
  (testing "backward transitions are invalid"
    (is (false? (phase/can-transition? :storage :inbound))))
  (testing "same-phase transitions are invalid"
    (is (false? (phase/can-transition? :storage :storage))))
  (testing "invalid phases never transition"
    (is (false? (phase/can-transition? :not-a-phase :storage)))
    (is (false? (phase/can-transition? :storage :not-a-phase)))))
