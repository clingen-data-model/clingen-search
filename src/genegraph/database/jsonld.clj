(ns genegraph.database.jsonld
  (:require [genegraph.database.query :as q]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
            [flatland.ordered.map :refer [ordered-map]])
  (:import [org.apache.jena.riot RDFParser RDFParserBuilder Lang RIOT]
           [org.apache.jena.rdf.model ModelFactory Model]
           [org.apache.jena.query Dataset DatasetFactory]
           org.apache.jena.sparql.util.Context
           com.github.jsonldjava.utils.JsonUtils))

(defn context [model]
  (let [members (get model [:skos/is-in-scheme :<])
        object-properties (map #(vector (csk/->snake_case (first (:rdfs/label %)))
                                        {"@type" "@vocab"
                                         "@id" (str %)})
                               (q/select "select ?x where { ?x :skos/is-in-scheme ?model FILTER EXISTS {{ ?x :rdfs/range :rdfs/Resource ; a :rdf/Property } UNION { ?x a :owl/ObjectProperty }} }" {:model model}))
        datatype-properties (map #(vector (csk/->snake_case (first (:rdfs/label %))) (str %))
                                 (q/select "select ?x where { ?x :skos/is-in-scheme ?model . FILTER EXISTS {{ ?x a :rdf/Property } UNION { ?x a :owl/DatatypeProperty } . FILTER NOT EXISTS { ?x :rdfs/range :rdfs/Resource } } }" {:model model}))
        classes (map #(vector (csk/->PascalCase (first (:rdfs/label %))) (str %))
                     (filter #(q/is-rdf-type? % :owl/Class) members))
        concepts (map #(vector (csk/->PascalCase (first (:rdfs/label %))) (str %))
                     (filter #(q/is-rdf-type? % :skos/Concept) members))
        individuals (map #(vector (csk/->PascalCase (first (:rdfs/label %))) (str %))
                     (filter #(q/is-rdf-type? % :owl/NamedIndividual) members))]
    (json/generate-string (into
                           (ordered-map "id" "@id"
                                        "type" "@type" 
                                        "PMID" "https://www.ncbi.nlm.nih.gov/pubmed/"
                                        "CLINVAR" "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                                        "CA" "http://reg.genome.network/allele/CA") 
                           (concat object-properties
                                   datatype-properties
                                   classes
                                   concepts
                                   individuals))
                          {:pretty true})))



(defn read-json-ld [document context]
  (let [model (ModelFactory/createDefaultModel)
        ds (DatasetFactory/create)
        context-obj (Context.)]
;;JsonUtils.fromInputStream(new ByteArrayInputStream(jsonldContext.getBytes(StandardCharsets.UTF_8)));
    (.set context-obj 
          RIOT/JSONLD_CONTEXT 
          (-> context .getBytes java.io.ByteArrayInputStream. JsonUtils/fromInputStream))
    (println context-obj)
    (-> (RDFParser/fromString document)
        (.lang Lang/JSONLD)
        (.context context-obj)
        (.parse model))
    model))

