(ns genegraph.source.graphql.resource
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.common.cache :refer [defresolver]]))

(defn iri [context args value]
  (str value))

(defn curie [context args value]
  (q/curie value))

(defresolver label [args resource]
  (first (concat (:skos/preferred-label resource)
                 (:rdfs/label resource)
                 (:foaf/name resource))))

(defresolver website-display-label [args resource]
  (first (concat (:cg/website-display-label resource)
                 (:skos/preferred-label resource)
                 (:rdfs/label resource)
                 (:foaf/name resource))))

(defresolver type [args value]
  (:rdf/type value))

(defresolver alternative-label [args resource]
  (first (:skos/alternative-label resource)))

(defresolver description [args value]
  (first (:dc/description value)))

(defresolver direct-superclasses [args value]
  (q/ld-> value [:rdfs/sub-class-of]))

(defresolver direct-subclasses [args value]
  (q/ld-> value [[:rdfs/sub-class-of :<]]))
