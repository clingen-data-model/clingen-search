(ns clingen-search.source.html.elements.evidence-level-assertion
  (:require [clingen-search.source.html.elements :as e]
            [clingen-search.database.query :as q]))

(defmethod e/detail-section :sepio/EvidenceLevelAssertion [assertion]
  [:div.container
   [:div.columns
    [:div.column (e/link (first (:sepio/has-predicate assertion)))]
    [:div.column (e/link (first (:sepio/has-object assertion)))]]
   (map e/row (:sepio/has-evidence-line-with-item assertion))])

(defmethod e/paragraph :sepio/EvidenceLevelAssertion [assertion]
  (let []
    [:p ]))
