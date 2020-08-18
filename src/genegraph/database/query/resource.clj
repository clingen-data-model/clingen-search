(ns genegraph.database.query.resource
  (:require [genegraph.database.instance :refer [db]]
            [genegraph.database.util :as util :refer [tx]]
            [genegraph.database.query.types :as types]
            [genegraph.database.names :as names :refer
             [local-class-names local-property-names
              class-uri->keyword local-names ns-prefix-map
              prefix-ns-map property-uri->keyword]]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.core.protocols :as protocols :refer [Datafiable]]
            [clojure.datafy :as datafy :refer [datafy nav]]
            [io.pedestal.log :as log]
            [medley.core :as medley]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import [org.apache.jena.rdf.model Model Statement ResourceFactory Resource Literal RDFList SimpleSelector ModelFactory]
           [org.apache.jena.query Dataset QueryFactory Query QueryExecution
            QueryExecutionFactory QuerySolutionMap]
           [org.apache.jena.sparql.algebra AlgebraGenerator Algebra OpAsQuery Op]
           [org.apache.jena.graph Node NodeFactory Triple Node_Variable Node_Blank]
[org.apache.jena.sparql.algebra.op OpDistinct OpProject OpFilter OpBGP OpConditional OpDatasetNames OpDiff OpDisjunction OpDistinctReduced OpExtend OpGraph OpGroup OpJoin OpLabel OpLeftJoin OpList OpMinus OpNull OpOrder OpQuad OpQuadBlock OpQuadPattern OpReduced OpSequence OpSlice OpTopN OpUnion OpTable ]
[org.apache.jena.sparql.core BasicPattern Var VarExprList QuadPattern Quad]
           org.apache.jena.riot.writer.JsonLDWriter
           org.apache.jena.sparql.core.Prologue
           org.apache.jena.riot.RDFFormat$JSONLDVariant
           java.io.ByteArrayOutputStream))

(defn to-ref [r]
  (types/to-ref r))

(defn to-clj [r model]
  (types/to-clj r model))

(defn to-rdf-node [v]
  (types/to-rdf-node v))

(defprotocol SelectQuery
  (select [query-def] [query-def params] [query-def params model]))

(defprotocol AsResource
  "Create an RDFResource given a reference"
  (resource [r] [ns-prefix r]))

(defn path [r]
  (types/path r))

(defn ld-> [r ks]
  (types/ld-> r ks))

(defn ld1-> [r ks]
  (types/ld1-> r ks))

(defn is-rdf-type? [r rdf-type]
  (types/is-rdf-type? r rdf-type))

(defn as-jena-resource [r]
  (types/as-jena-resource r))

(defn get-all-graphs []
  (or @util/current-union-model (.getUnionModel db)))

(defn curie
  "Return a curie string for resource. Return the IRI of the resource if no prefix has been defined"
  [resource]
  (names/curie (str resource)))

(declare navize)

(def query-sort-order 
  {:ASC Query/ORDER_ASCENDING
   :asc Query/ORDER_ASCENDING
   :DESC Query/ORDER_DESCENDING
   :desc Query/ORDER_DESCENDING})

(defn construct-query-with-params [query query-params]
  (if-let [params (:genegraph.database.query/params query-params)]
    (let [modified-query (.clone query)]
      (when (:distinct params)
        (.setDistinct modified-query true))
      (when (:limit params)
        (.setLimit modified-query (:limit params)))
      (when (:offset params)
        (.setOffset modified-query (:offset params)))
      (when (:sort params)
        (let [{:keys [field direction]} (:sort params)]
          (.addResultVar modified-query (s/lower-case (name field)))
          (.addOrderBy modified-query (s/lower-case (name field)) (query-sort-order direction))))
      modified-query)
    query))

(defn path [r]
  (let [uri (str resource)
        short-ns (names/curie uri)
        full-ns (prefix-ns-map short-ns)
        id (subs uri (count full-ns))]
    (str "/r/" short-ns "_" id)))

(defn- navize [model]
  (fn [coll k v]
    (let [target (:genegraph.database.query/value (meta v))]
      (if (instance? Resource target)
        (types/->RDFResource target model)
        target))))

(defn- substitute-known-iri-short-name [k-ns k]
  (when-let [iri (some-> (keyword k-ns k) local-names .getURI)]
    (str "<" iri ">")))

(defn- substitute-known-ns [k-ns k]
  (when-let [base (names/prefix-ns-map k-ns)]
    (str "<" base k ">")))

(defn- substitute-keyword [[_ k-ns k]]
  (let [kw (keyword k-ns k)]
    (cond (local-names kw) (str "<" (-> kw local-names .getURI) ">")
          (prefix-ns-map k-ns) (str "<" (prefix-ns-map k-ns) k ">")
          :else (str ":" k-ns "/" k))))

;; TODO fix so that non-whitespace terminated expressions are treated appropriately
(defn- expand-query-str [query-str]
  (s/replace query-str #":(\S+)/(\S+)" substitute-keyword))

(defn- construct-query-solution-map [params]
  (let [qs-map (QuerySolutionMap.)]
    (doseq [[k v] params]
      (.add qs-map (name k) (to-rdf-node v)))
    qs-map))

(extend-protocol SelectQuery
  
  ;; TODO--consider removing this method into a function, do not want to expose
  ;; interfaces against Jena types
  ;;
  ;; The whole db-or-model ugliness below is due to an issue where QueryExecutionFactory/create
  ;; has the same method signature where the second param is either a Dataset or a Model.
  ;; If it is a Dataset that supports text indexing, we need to use the Dataset form otherwise
  ;; TextIndexPF complains about not finding a text index if we are doing a text search.
  ;; Also, the Dataset form needs to call .getUnionModel when resolving to Resources.
  ;; If we are querying (non-text) against a plain old Model then there is no .getUnionModel
  ;; call supported.
  ;;
  Query
  (select
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params db))
    ([query-def params db-or-model]
     (log/info :fn :select-query
                :msg "Executing select query"
                :query query-def
                :params params)
     (let [query (construct-query-with-params query-def params)
           model-from-params (:genegraph.database.query/model params)
           qs-map (construct-query-solution-map (dissoc params :genegraph.database.query/model :genegraph.database.query/params))]
       (tx
        (with-open [qexec (QueryExecutionFactory/create query db-or-model qs-map)]
          (when-let [result (-> qexec .execSelect)]
            (let [result-var (-> result .getResultVars first)
                  result-seq (iterator-seq result)
                  ;; TODO - needs to be refactored to not use type
                  model (if (instance? org.apache.jena.query.Dataset db-or-model)
                          (.getUnionModel db-or-model)
                          db-or-model)]
              (mapv #(types/->RDFResource (.getResource % result-var) model) result-seq))))))))
  
  java.lang.String
  (select 
    ([query-def] (select query-def {}))
    ([query-def params] (select query-def params (or (:genegraph.database.query/model params) db)))
    ([query-def params db-or-model]
     (select (QueryFactory/create (expand-query-str query-def)) params db-or-model))))

(extend-protocol AsResource
  
  java.lang.String
  (resource 
    ([r] (let [[_ curie-prefix rest] (re-find #"^([a-zA-Z]+)[:_](.*)$" r)
               iri (if-let [iri-prefix (-> curie-prefix s/lower-case prefix-ns-map)]
                     (str iri-prefix rest)
                     r)]
           (types/->RDFResource (ResourceFactory/createResource iri) (get-all-graphs))))
    ([ns-prefix r] (when-let [prefix (prefix-ns-map ns-prefix)]
                     (types/->RDFResource (ResourceFactory/createResource (str prefix r))
                                    (get-all-graphs)))))
  
  clojure.lang.Keyword
  (resource [r] (when-let [res (local-names r)]
                  (types/->RDFResource res (get-all-graphs)))))

(defn construct 
  ([query-string] (construct query-string {}))
  ([query-string params] (construct query-string {} (get-all-graphs)))
  ([query-string params model]
   (let [query (QueryFactory/create (expand-query-str query-string))
         qs-map (construct-query-solution-map (dissoc params :-model))]
     (tx
      (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
        (.execConstruct qexec))))))

(defn get-named-graph [name]
  (.getNamedModel db name))

(defn list-named-graphs []
  (into [] (iterator-seq (.listNames db))))

(defn to-turtle [model]
  (let [os (ByteArrayOutputStream.)]
    (.write model os "TURTLE")
    (.toString os)))

(defn union [& models]
  (let [union-model (ModelFactory/createDefaultModel)]
    (doseq [model models] (.add union-model model))
    union-model))


;;;; Query builder

(defn text-index [var ])

(defn triple
  "Construct triple for use in BGP. Part of query algebra."
  [stmt]
  (let [[s p o] stmt
        subject (cond
                  (instance? Node s) s
                  (= '_ s) Var/ANON
                  (symbol? s) (Var/alloc (str s))
                  (keyword? s) (.asNode (local-class-names s))
                  (string? s) (NodeFactory/createURI s)
                  (types/resource? o) (.asNode (as-jena-resource s))
                  :else (NodeFactory/createURI (str s)))
        predicate (cond
                    (instance? Node p) p
                    (keyword? p) (.asNode (local-property-names p))
                    :else (NodeFactory/createURI (str p)))
        object (cond 
                 (instance? Node o) o
                 (symbol? o) (Var/alloc (str o))
                 (keyword? o) (.asNode (or (local-class-names o) (local-property-names o)))
                 (or (string? o)
                     (int? o)
                     (float? o)) (.asNode (ResourceFactory/createTypedLiteral o))
                 (types/resource? o) (.asNode (as-jena-resource o))
                 :else o)]
    (Triple. subject predicate object)))

;; (defn expr
;;   "Convert a Clojure data structure to an Arq Expr"
;;   [expr]
;;   (if (instance? java.util.List expr)
;;     (composite-expr expr)
;;     (let [node (graph/node expr)]
;;       (if (instance? Node_Variable node)
;;         (ExprVar. (Var/alloc ^Node_Variable node))
;;         (NodeValue/makeNode node)))))

;; (defn- var-expr-list
;;   "Given a vector of var/expr bindings (reminiscient of Clojure's `let`), return a Jena VarExprList with vars and exprs."
;;   [bindings]
;;   (let [vel (VarExprList.)]
;;     (doseq [[v e] (partition 2 bindings)]
;;       (.add vel (Var/alloc (graph/node v))
;;         (expr e)))
;;     vel))

;; (defn- var-aggr-list
;;   "Given a vector of var/aggregate bindings return a Jena VarExprList with
;;    vars and aggregates"
;;   [bindings]
;;   (vec (for [[v e] (partition 2 bindings)]
;;          (ExprAggregator. (Var/alloc (graph/node v)) (aggregator e)))))

;; (defn- sort-conditions
;;   "Given a seq of expressions and the keyword :asc or :desc, return a list of
;;    sort conditions."
;;   [conditions]
;;   (for [[e dir] (partition 2 conditions)]
;;     (SortCondition. ^Expr (expr e) (if (= :asc dir) 1 -1))))

(defn- var-seq [vars]
  (map #(Var/alloc (str %)) vars))

(declare op)

(defn- op-union [a1 a2 & amore]
  (OpUnion. 
   (op a1)
   (if amore
     (apply op-union a2 amore)
     (op a2))))

(defn op
  "Convert a Clojure data structure to an Arq Op"
  [[op-name & [a1 a2 & amore :as args]]]
  (case op-name
    :distinct (OpDistinct/create (op a1))
    :project (OpProject. (op a2) (var-seq a1))
    ;; :filter (OpFilter/filterBy (ExprList. ^List (map expr (butlast args))) (op (last args)))
    :bgp (OpBGP. (BasicPattern/wrap (map triple args)))
    :conditional (OpConditional. (op a1) (op a2))
    :diff (OpDiff/create (op a1) (op a2))
    :disjunction (OpDisjunction/create (op a1) (op a2))
    ;; :extend (OpExtend/create (op a2) (var-expr-list a1))
    ;; :group (OpGroup/create (op (first amore))
    ;;                        (VarExprList. ^List (var-seq a1))
    ;;                        (var-aggr-list a2))
    :join (OpJoin/create (op a1) (op a2))
    :label (OpLabel/create a1 (op a2))
    ;; :left-join (OpLeftJoin/create (op a1) (op a2) (ExprList. ^List (map expr amore)))
    :list (OpList. (op a1))
    :minus (OpMinus/create (op a1) (op a2))
    :null (OpNull/create)
    ;; :order (OpOrder. (op a2) (sort-conditions a1))
    :reduced (OpReduced/create (op a1))
    :sequence (OpSequence/create (op a1) (op a2))
    :slice (OpSlice. (op a1) (long a1) (long (first amore)))
    ;; :top-n (OpTopN. (op (first amore)) (long a1) (sort-conditions a2))
    :union (apply op-union args)
    (throw (ex-info (str "Unknown operation " op-name) {:op-name op-name
                                                        :args args}))))


(defn- compose-select-result [qexec model]
  (when-let [result (-> qexec .execSelect)]
    (let [result-var (-> result .getResultVars first)
          result-seq (iterator-seq result)
          node-model (if (instance? Dataset model)
                       (get-all-graphs)
                       model)]
      (mapv #(types/->RDFResource (.getResource % result-var) node-model) result-seq))))

(defn- exec [query-def params]
  (let [qs-map (construct-query-solution-map (medley/filter-keys #(nil? (namespace %)) params))
        model (or (:genegraph.database.query/model params) db)
        result-model (or (:genegraph.database.query/model params) (get-all-graphs))
        query (construct-query-with-params query-def params)]
    (tx
     (with-open [qexec (QueryExecutionFactory/create query model qs-map)]
       (cond 
         (.isConstructType query) (.execConstruct qexec)
         (.isSelectType query) (if (= :count (get-in params [:genegraph.database.query/params :type]))
                                 (-> qexec .execSelect iterator-seq count)
                                 (compose-select-result qexec result-model))
         (.isAskType query) (.execAsk qexec))))))

(deftype StoredQuery [query]
  clojure.lang.IFn
  (invoke [this] (this {}))
  (invoke [this params] (exec query params))
  
  Object
  (toString [_] (str query)))

(defn create-query 
  "Return parsed query object. If query is not a string, assume object that can
use io/slurp"
  ([query-source] (create-query query-source {}))
  ([query-source params]
   (let [query (if  (coll? query-source)
                  (OpAsQuery/asQuery (op query-source))
                  (QueryFactory/create (expand-query-str
                                        (if (string? query-source)
                                          query-source
                                          (slurp query-source)))))]
     (case (:genegraph.database.query/type params)
       :ask (.setQueryAskType query)
       (.setDistinct query true))
     (->StoredQuery query))))