(ns metabase.sync.analyze.classifiers.name
  "Classifier that infers the special type of a Field based on its name and base type."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase
             [config :as config]
             [util :as u]]
            [metabase.models
             [database :refer [Database]]
             [field :refer [Field] :as field]
             [table :as table]]
            [metabase.sync
             [interface :as i]
             [util :as sync-util]]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]))

(def ^:private bool-or-int-type #{:type/Boolean :type/Integer})
(def ^:private float-type       #{:type/Float})
(def ^:private int-type         #{:type/Integer})
(def ^:private int-or-text-type #{:type/Integer :type/Text})
(def ^:private text-type        #{:type/Text})
(def ^:private timestamp-type   #{:type/DateTime})

(def ^:private pattern+base-types+special-type
  "Tuples of `[name-pattern set-of-valid-base-types special-type]`.
   Fields whose name matches the pattern and one of the base types should be given the special type.

   *  Convert field name to lowercase before matching against a pattern
   *  Consider a nil set-of-valid-base-types to mean \"match any base type\""
  [[#"^.*_lat$"       float-type       :type/Latitude]
   [#"^.*_lon$"       float-type       :type/Longitude]
   [#"^.*_lng$"       float-type       :type/Longitude]
   [#"^.*_long$"      float-type       :type/Longitude]
   [#"^.*_longitude$" float-type       :type/Longitude]
   [#"^.*_rating$"    int-or-text-type :type/Category]
   [#"^.*_type$"      int-or-text-type :type/Category]
   [#"^.*_url$"       text-type        :type/URL]
   [#"^_latitude$"    float-type       :type/Latitude]
   [#"^active$"       bool-or-int-type :type/Category]
   [#"^city$"         text-type        :type/City]
   [#"^country$"      text-type        :type/Country]
   [#"^countryCode$"  text-type        :type/Country]
   [#"^currency$"     int-or-text-type :type/Category]
   [#"^first_name$"   text-type        :type/Name]
   [#"^full_name$"    text-type        :type/Name]
   [#"^gender$"       int-or-text-type :type/Category]
   [#"^last_name$"    text-type        :type/Name]
   [#"^lat$"          float-type       :type/Latitude]
   [#"^latitude$"     float-type       :type/Latitude]
   [#"^lon$"          float-type       :type/Longitude]
   [#"^lng$"          float-type       :type/Longitude]
   [#"^long$"         float-type       :type/Longitude]
   [#"^longitude$"    float-type       :type/Longitude]
   [#"^name$"         text-type        :type/Name]
   [#"^postalCode$"   int-or-text-type :type/ZipCode]
   [#"^postal_code$"  int-or-text-type :type/ZipCode]
   [#"^rating$"       int-or-text-type :type/Category]
   [#"^role$"         int-or-text-type :type/Category]
   [#"^sex$"          int-or-text-type :type/Category]
   [#"^state$"        text-type        :type/State]
   [#"^status$"       int-or-text-type :type/Category]
   [#"^type$"         int-or-text-type :type/Category]
   [#"^url$"          text-type        :type/URL]
   [#"^zip_code$"     int-or-text-type :type/ZipCode]
   [#"^zipcode$"      int-or-text-type :type/ZipCode]
   [#"discount"       float-type       :type/Discount]
   [#"income"         float-type       :type/Income]
   [#"amount"         float-type       :type/Income]
   [#"total"          float-type       :type/Income]
   [#"price"          float-type       :type/Price]
   [#"quantity"       int-type         :type/Quantity]
   [#"count$"         int-type         :type/Quantity]
   [#"join"           timestamp-type   :type/JoinTimestamp]
   [#"create"         timestamp-type   :type/CreationTimestamp]
   [#"source"         text-type        :type/Source]
   [#"channel"        text-type        :type/Source]])

;; Check that all the pattern tuples are valid
(when-not config/is-prod?
  (doseq [[name-pattern base-types special-type] pattern+base-types+special-type]
    (assert (instance? java.util.regex.Pattern name-pattern))
    (assert (every? (u/rpartial isa? :type/*) base-types))
    (assert (isa? special-type :type/*))))

(s/defn ^:private special-type-for-name-and-base-type :- (s/maybe su/FieldType)
  "If `name` and `base-type` matches a known pattern, return the `special_type` we should assign to it."
  [field-name :- su/NonBlankString, base-type :- su/FieldType]
  (or (when (= "id" (str/lower-case field-name)) :type/PK)
      (some (fn [[name-pattern valid-base-types special-type]]
              (when (and (some (partial isa? base-type) valid-base-types)
                         (re-find name-pattern (str/lower-case field-name)))
                special-type))
            pattern+base-types+special-type)))

(defn infer-special-type
  "Classifer that infers the special type of a FIELD based on its name and base type."
  [field _]
  (let [inferred-special-type (special-type-for-name-and-base-type (:name field) (:base_type field))]
    (if inferred-special-type
      (do
        (log/debug (format "Based on the name of %s, we're giving it a special type of %s."
                           (-> (field/->FieldInstance)
                               (merge field)
                               sync-util/name-for-logging)
                           inferred-special-type))
        (assoc field :special_type inferred-special-type))
      field)))

(def ^:private entity-types-patterns
  [[#"order"       :type/TransactionTable]
   [#"transaction" :type/TransactionTable]
   [#"sale"        :type/TransactionTable]
   [#"product"     :type/ProductTable]
   [#"user"        :type/UserTable]
   [#"account"     :type/UserTable]
   [#"people"      :type/UserTable]
   [#"person"      :type/UserTable]
   [#"event"       :type/EventTable]
   [#"checkin"     :type/EventTable]
   [#"log"         :type/EventTable]])

(defn infer-entity-type
  "Classifer that infers the entity type of a TABLE based on its name."
  [table]
  (let [table-name  (-> table :name str/lower-case)
        entity-type (or (some (fn [[pattern type]]
                                          (when (re-find pattern table-name)
                                            type))
                                        entity-types-patterns)
                                  (case (-> table
                                            :db_id
                                            Database
                                            :engine)
                                    :googleanalytics :type/GoogleAnalyticsTable
                                    :druid           :type/EventTable
                                    nil)
                                  :type/GenericTable)]
    (log/debug (format "Based on the name of %s, we're giving it entity type %s."
                       (-> (table/->TableInstance)
                           (merge table)
                           sync-util/name-for-logging)
                       entity-type))
    (assoc table :entity_type entity-type)))
