(ns genegraph.source.graphql.core
  (:require [genegraph.database.query :as q]
            [genegraph.source.graphql.gene :as gene]
            [genegraph.source.graphql.resource :as resource]
            [genegraph.source.graphql.actionability :as actionability]
            [genegraph.source.graphql.gene-dosage :as gene-dosage]
            [genegraph.source.graphql.condition :as condition]
            [genegraph.source.graphql.server-status :as server-status]
            [genegraph.source.graphql.evidence :as evidence]
            [genegraph.source.graphql.value-set :as value-set]
            [genegraph.source.graphql.class :as rdf-class]
            [genegraph.source.graphql.property :as property]
            [genegraph.source.graphql.coordinates :as coordinates]
            [genegraph.source.graphql.dosage-curation :as dosage-curation]
            [genegraph.source.graphql.gene-facts :as gene-facts]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def base-schema 
  {:interfaces
   {:resource
    {:description "An RDF Resource; generic type suitable for return when a variety of resources may be returned as the result of a function all"
     :fields {:iri {:type 'String}
              :label {:type 'String}}}
    :curation 
    {:fields {:wg_label {:type 'String}
              :classification_description {:type 'String}
              :report_date {:type 'String}}}}

   :objects
   {:coordinates
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :build {:type 'String
                      :description "The build name"
                      :resolve coordinates/build}
              :chromosome {:type 'String
                           :description "The chromosome name"
                           :resolve coordinates/chromosome}
              :start_pos {:type 'Int
                          :description "Start coordinate of the gene"
                          :resolve coordinates/start-pos}
              :end_pos {:type 'Int
                        :description "End coordinate of the gene"
                        :resolve coordinates/end-pos}}}

    :gene_facts
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :hgnc_symbol {:type 'String
                            :description "The HGNC symbol of the gene"
                            :resolve gene-facts/hgnc-symbol}
              :hgnc_name {:type 'String
                          :description "The HGNC name for the gene"
                          :resolve gene-facts/hgnc-name}
              :gene_type {:type 'String
                          :description "The gene type"
                          :resolve gene-facts/gene-type}
              :locus_type {:type 'String
                           :description "The gene locus type"
                           :resolve gene-facts/locus-type}
              :previous_symbols {:type 'String
                                 :description "The list of previous gene symbols"
                                 :resolve gene-facts/previous-symbols}
              :alias_symbols {:type 'String
                              :description "The list of gene aliases"
                              :resolve gene-facts/alias-symbols}
              :chromo_loc {:type 'String
                           :description "The chromosomal location"
                           :resolve gene-facts/chromo-loc}
              :function {:type 'String
                         :description "A description of the genes function"
                         :resolve gene-facts/function}
              :coordinates {:type '(list :coordinates)
                            :description "A list of the gene coordinates"
                            :resolve gene-facts/coordinates}}}
    :gene
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :hgnc_id {:type 'String :resolve gene/hgnc-id}
              :gene_facts {:type :gene_facts :resolve gene/gene-facts}
              :curations {:type '(list :curation) :resolve gene/curations}
              :conditions {:type '(list :condition) :resolve gene/conditions}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve gene/actionability-curations}
              :dosages {:type '(list :gene_dosage)
                                 :resolve gene/dosages}}}


    :condition
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve condition/iri}
              :label {:type 'String :resolve condition/label}
              :gene {:type :gene :resolve condition/gene}
              :actionability_curations {:type '(list :actionability_curation)
                                        :resolve condition/actionability-curations}
              :genetic_conditions {:type '(list :condition)
                                   :resolve condition/genetic-conditions}}}

    :evidence
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :source {:type 'String :resolve evidence/source}
              :description {:type 'String :resolve evidence/description}}}

    :dosage_curation
    {:implements [:resource :curation]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve resource/label}
              :wg_label {:type 'String :resolve dosage-curation/wg-label}
              :classification_description {:type 'String
                                           :description "A statement about the strength of evidence" 
                                           :resolve dosage-curation/classification-description}

              :report_date {:type 'String
                            :description "The date this curation was reported"
                            :resolve dosage-curation/report-date}
              :score {:type 'Int
                      :description "Sufficiency score"
                      :resolve dosage-curation/score}
              :phenotypes {:type 'String
                          :description "The phenotypes to which the evidence applies"
                          :resolve dosage-curation/phenotypes}
              :evidence {:type '(list :evidence)
                          :description "Evidence statements"
                          :resolve dosage-curation/evidence}
              :comments {:type 'String
                         :description "Comments"
                         :resolve dosage-curation/comments}}}
    
    :gene_dosage
    {:implements [:resource]
     :fields {:iri {:type 'String :resolve resource/iri}
              :label {:type 'String :resolve gene-dosage/label}
              :wg_label {:type 'String :resolve gene-dosage/wg-label}
              :report_date {:type 'String :resolve gene-dosage/report-date}
              :gene_name {:type 'String :resolve gene-dosage/gene-name}
              :gene {:type :gene :resolve gene-dosage/gene}
              :haplo {:type :dosage_curation
                       :description "Haploinsufficiency"
                       :resolve gene-dosage/haplo}
              :triplo {:type :dosage_curation
                       :description "Triplosensitivity"
                       :resolve gene-dosage/triplo}
              :haplo_index {:type 'String
                            :description "Haploinsufficiency index percent"
                            :resolve gene-dosage/haplo-index}
              :morbid {:type 'Boolean :resolve gene-dosage/morbid}
              :omim {:type 'Boolean
                     :description ""
                     :resolve gene-dosage/omim}
              :pli_score {:type 'String
                          :description "Loss intolerence (pLI)"
                          :resolve gene-dosage/pli-score}
              :location_relationship {:type 'String :resolve gene-dosage/location-relationship}}}
    
    :actionability_curation
    {:implements [:resource :curation]
     :fields 
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :report_date {:type 'String :resolve actionability/report-date}
      :wg_label {:type 'String :resolve actionability/wg-label}
      :report_id {:type 'String :resolve actionability/report-id}
      :classification_description {:type 'String :resolve actionability/classification-description}
      :conditions {:type '(list :condition)
                   :resolve actionability/conditions}
      :source {:type 'String :resolve actionability/source}}}

    :server_status
    {:fields
     {:migration_version {:type 'String
                          :resolve server-status/migration-version}}}

    :concept
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}}}


    :property
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve property/iri}
      :label {:type 'String :resolve property/label}
      :definition {:type 'String :resolve property/definition}
      :min {:type 'Int :resolve property/min-count}
      :max {:type 'Int :resolve property/max-count}
      :display_arity {:type 'String :resolve property/display-arity}}}

    :class
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}
      :properties {:type '(list :property) :resolve rdf-class/properties}
      :subclasses {:type '(list :class) :resolve rdf-class/subclasses}
      :superclasses {:type '(list :class) :resolve rdf-class/superclasses}}}

    :value_set
    {:implements [:resource]
     :fields
     {:iri {:type 'String :resolve resource/iri}
      :label {:type 'String :resolve resource/label}
      :definition {:type 'String :resolve rdf-class/definition}
      :concepts {:type '(list :concept) :resolve value-set/concepts}}}

    :totals
    {:fields
     {:total {:type 'Int :resolve gene-dosage/total-count}
      :genes  {:type 'Int :resolve gene-dosage/gene-count}
      :regions  {:type 'Int :resolve gene-dosage/region-count}}}}
  
   :queries
   {:gene {:type '(non-null :gene)
           :args {:iri {:type 'String}}
           :resolve gene/gene-query}
    :condition {:type '(non-null :condition)
                :args {:iri {:type 'String}}
                :resolve condition/condition-query}
    :actionability {:type '(non-null :actionability_curation)
                    :args {:iri {:type 'String}}
                    :resolve actionability/actionability-query}
    :value_sets {:type '(list :value_set)
                 :resolve value-set/value-sets-query}
    :model_classes {:type '(list :class)
                    :resolve rdf-class/model-classes-query}
    :server_status {:type '(non-null :server_status)
                    :resolve server-status/server-version-query}
    :dosage_list{:type '(list :gene_dosage)
               :resolve gene-dosage/dosage-list-query}
    :totals {:type :totals
             :resolve gene-dosage/totals-query}}})

(defn schema []
  (schema/compile base-schema))

(defn gql-query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  [query-str]
  (lacinia/execute (schema) query-str nil nil))
