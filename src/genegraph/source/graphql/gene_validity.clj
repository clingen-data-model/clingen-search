(ns genegraph.source.graphql.gene-validity
  (:require [genegraph.database.query :as q :refer [ld-> ld1-> create-query resource]]
            [genegraph.source.graphql.common.enum :as enum]
            [genegraph.source.graphql.common.curation :as curation]
            [genegraph.source.graphql.common.cache :refer [defresolver]]
            [clojure.string :as s]))

;; CGGV:assertion_43fb4e99-e97a-4d9c-af11-79c2b09ecd2e-2019-07-24T160000.000Z
;; CGGCIEX:assertion_10075
;; https://search.clinicalgenome.org/kb/gene-validity/3210
;; https://search.clinicalgenome.org/kb/gene-validity/92e04f9e-f03e-4295-baac-e9fb6b48a258--2020-06-29T17:45:43

(defn find-newest-gci-curation [id]
  (when-let [uuid-part (re-find #"\w+-\w+-\w+-\w+-\w+" id)]
    (let [proposition (q/resource (str "CGGV:proposition_" uuid-part))]
      (when (q/is-rdf-type? proposition :sepio/GeneValidityProposition)
        (ld1-> proposition [[:sepio/has-subject :<]])))))

(defn find-gciex-curation [id]
  (when (re-matches #"\d+" id)
    (let [gciex-assertion (q/resource (str "CGGCIEX:assertion_" id))]
      (when (q/is-rdf-type? gciex-assertion :sepio/GeneValidityEvidenceLevelAssertion)
        gciex-assertion))))

(defresolver gene-validity-assertion-query [args value]
  (let [requested-assertion (q/resource (:iri args))]
    (if (q/is-rdf-type? requested-assertion :sepio/GeneValidityEvidenceLevelAssertion)
      requested-assertion
      (or (find-newest-gci-curation (:iri args))
          (find-gciex-curation (:iri args))))))

(defresolver report-date [args value]
  (ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))


;; DEPRECATED
(defresolver gene-validity-list [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))]
    (curation/gene-validity-curations {::q/params params})))

(defresolver ^:expire-always gene-validity-curations [args value]
  (curation/gene-validity-curations-for-resolver args value))

;; DEPRECATED -- may not be used at all
(defresolver criteria [args value]
  nil)

(def evidence-levels
  {:sepio/DefinitiveEvidence :DEFINITIVE
   :sepio/LimitedEvidence :LIMITED
   :sepio/ModerateEvidence :MODERATE
   :sepio/NoEvidence :NO_KNOWN_DISEASE_RELATIONSHIP
   :sepio/RefutingEvidence :REFUTED
   :sepio/DisputingEvidence :DISPUTED
   :sepio/StrongEvidence :STRONG})

(defresolver classification [args value]
  (-> value :sepio/has-object first))

(defresolver gene [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-subject]))

(defresolver disease [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-object]))

(defresolver mode-of-inheritance [args value]
  (ld1-> value [:sepio/has-subject :sepio/has-qualifier]))

;; (defresolver attributed-to [args value]
  
;;   (ld1-> value [:sepio/qualified-contribution :sepio/has-agent]))

(def primary-attribution-query
  (q/create-query
   "select ?agent where {
    ?assertion :sepio/qualified-contribution ?contribution . 
    ?contribution :bfo/realizes :sepio/ApproverRole ;
    :sepio/has-agent ?agent . } 
   limit 1 "))

(defresolver attributed-to [args value]
  (first (primary-attribution-query {:assertion value})))

(defresolver contributions [args value]
  (:sepio/qualified-contribution value))

(defresolver specified-by [args value]
  (ld1-> value [:sepio/is-specified-by]))

(defresolver has-format [args value]
  (ld1-> value [:dc/has-format]))

(defn legacy-json [_ _ value]
  (ld1-> value [[:bfo/has-part :<] :bfo/has-part :cnt/chars]))
