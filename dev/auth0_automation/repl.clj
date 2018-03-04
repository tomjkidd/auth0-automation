(ns auth0-automation.repl
  (:require [auth0-automation.auth0 :as auth0]
            [auth0-automation.core :as core]))

(def env-config core/env-config)

(defn get-token
  "Get an Auth0 Maintenance API access-token"
  []
  (auth0/get-token env-config))

(defn get-entity
  "Get an Auth0 Mantenance API entity, given `m`, with `:type` and `:id`"
  ([m]
   (get-entity m (get-token)))
  ([m token]
   (auth0/get-entity (assoc m :domain (get-in env-config [:auth0 :domain]) :token token))))

(defn get-entity-cache
  "Get all of the entities for the specified types, for repl investigation"
  ([]
   (get-entity-cache (get-token)))
  ([token]
   (reduce (fn [acc entity-type]
             (assoc acc entity-type (auth0/get-entities {:domain (get-in env-config [:auth0 :domain])
                                                         :type   entity-type
                                                         :token  token})))
           {}
           [:client :resource-server :connection :user
            :rule :rules-config :grant :email])))

(comment
  (defn resolve-payload
    "Figure out the differences between the existing Auth0 entity and the proposed edn-entity,
  constructing a payload that can be sent to Auth0 to update the existing entity to be configured
  as specified by edn-entity."
    [auth0-entity edn-entity]
    edn-entity)

  (defn determine-api-action
    "Creates a data structure that indicates how to process the changes necessary in the Auth0 environment"
    [auth0-entity edn-entity]
    (if auth0-entity
      (if (noop? auth0-entity {:keys [type key payload] :as edn-entity})
        {:type :noop
         :msg (format "The %s %s already exists, and is configured correctly" type (key payload))}
        {:type      :update
         :entity-id (:key auth0-entity)
         :payload   (resolve-payload auth0-entity edn-entity)})
      {:type :create
       :payload (:payload edn-entity)}))

  (defn determine-api-actions
    "api-action: A hash-map with `:type` and `:payload` keys.
  `:type` can be one of `#{:create :update :noop}`
  A `:noop` means that the entity exists and is configured as intended.
  `:payload` is the json payload to use with the api call in order to put the desired entity into the right state"
    [{:keys [type key]}]
    (doseq [entity auth0-environment-config]
      (let [entities (fetch-all-of-type type)
            current  (first (filter key entities))]
        (determine-api-action current entity)))))
