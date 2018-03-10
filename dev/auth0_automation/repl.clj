(ns auth0-automation.repl
  (:require [auth0-automation.api-action :as api-action]
            [auth0-automation.auth0 :as auth0]
            [auth0-automation.core :as core]
            [auth0-automation.util :as util]
            [clojure.pprint :as pp]))

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

(defn get-edn-config
  "Return the `edn-config` data"
  []
  (util/load-edn-config env-config))

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
            :client-grant :rule :rules-config :grant :email])))

(defn tinker
  "Demonstrates api-action determination (relies on a private environment and config...)"
  []
  (let [token (get-token)
        edn-config (get-edn-config)
        api-actions (api-action/determine-api-actions token edn-config env-config)]
    (pp/pprint api-actions)))
