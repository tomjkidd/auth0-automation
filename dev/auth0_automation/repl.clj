(ns auth0-automation.repl
  (:require [auth0-automation.auth0 :as auth0]
            [auth0-automation.core :as core]
            [camel-snake-kebab.core :refer [->snake_case ->kebab-case]]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.pprint :refer [pprint]]
            [clj-http.client :as client]))

(def env-config core/env-config)

(def auth0-domain (get-in env-config [:auth0 :domain]))

(def url-mappings
  {:client          "https://%s/api/v2/clients"
   :resource-server "https://%s/api/v2/resource-servers"
   :connection      "https://%s/api/v2/connections"
   :user            "https://%s/api/v2/users"})

(defn build-url*
  [url-fmt-mappings api-url-key & args]
  (apply format (api-url-key url-fmt-mappings) args))

(defn build-url
  [api-url-key & args]
  (apply build-url* url-mappings api-url-key auth0-domain args))

(defn get-token
  []
  (auth0/get-token env-config))

(defonce token (get-token))

(defn deserialize
  [json]
  (parse-string json (comp keyword ->kebab-case)))

(defn get-entities
  "`type` keyword. One of #{:client :resource-server}"
  [type token]
  (-> (client/get (build-url type)
                  {:headers {"Authorization" (format "Bearer %s" token)}})
      :body
      deserialize))

(defn get-entity
  [{:keys [type id]} auth0-entity token]
  (-> (client/get (format "%s/%s" (build-url type) (id auth0-entity))
                  {:headers {"Authorization" (format "Bearer %s" token)}})
      :body
      deserialize))

(def entity-cache
  (reduce (fn [acc entity-type]
            (assoc acc entity-type (get-entities entity-type token)))
          {}
          (keys url-mappings)))

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
