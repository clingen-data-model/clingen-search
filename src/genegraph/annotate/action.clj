(ns genegraph.annotate.action
  (:require [cheshire.core :as json]))

(defmulti add-action :genegraph.transform.core/format)

(defmethod add-action :default [event]
  event)

(defmethod add-action :gci-legacy [event]
  (let [report-json (json/parse-string (:genegraph.sink.event/value event) true)]
    (assoc event 
           :genegraph.annotate/action 
           (case (:statusPublishFlag report-json)
             "Publish" :publish
             "Unpublish" :unpublish))))
