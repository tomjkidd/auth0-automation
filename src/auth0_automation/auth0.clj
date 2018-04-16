(ns auth0-automation.auth0
  (:require [auth0-automation.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clj-http.client :as client]))

(defmulti api-endpoint
  "Takes a type and returns the api-endpoint string for that type

  For example, :client -> \"clients\"
  NOTE: Not all supported Auth0 API types are configured, but this is available
  to make those cases easy."
  identity)

(defmethod api-endpoint
  :email
  [type]
  (format "%ss/provider" (name type)))

(defmethod api-endpoint
  :default
  [type]
  (-> type
      name
      (str "s")))

(defmulti build-url
  "Gets the base url format string for the given entity type.

  By default, this is in the form `http://<domain>/api/v2/<api-endpoint>`
  The intent is for if there is an inconsistency, it can be identified
  and handled to support all of the Auth0 API types."
  (fn [domain type] type))

(defmethod build-url
  :audience
  [domain type]
  (format "https://%s/api/v2/" domain))

(defmethod build-url
  :token
  [domain type]
  (format "https://%s/oauth/token" domain))

(defmethod build-url
  :default
  [domain type]
  (->> type
       api-endpoint
       (format "https://%s/api/v2/%s" domain)))

(defn get-token-response
  "Manages the post to Auth0 to get an `access_token`"
  [{:keys [auth0]}]
  (let [{:keys [domain client-id client-secret]} auth0

        url       (build-url domain :token)
        body      {:grant-type    "client_credentials"
                   :client-id     client-id
                   :client-secret client-secret
                   :audience      (build-url domain :audience)}]
    (client/post url
                 {:content-type     :json
                  :body             (util/serialize body)
                  :accept           :json
                  :throw-exceptions false})))

(defn success?
  [{:keys [status]}]
  (contains? #{200 201} status))

(defn get-token
  "Use the environment variables to get a Management API token.

  Will return the token if successful, and nil otherwise"
  [env-config]
  (let [response (get-token-response env-config)]
    (when (success? response)
      (-> response
          :body
          util/deserialize
          :access-token))))

(defn get-entity
  "Perform an Auth API get for the specific entity of `type` identified by `id`, using `token` for authz"
  [{:keys [domain type id token]}]
  (util/http-get (format "%s/%s" (build-url domain type) id) token))

(defn get-entities-using-strategy
  "Custom get-entities in order to support types like email-templates"
  [{:keys [domain type token entity-manipulation-config]}]
  (let [{:keys [strategy] :as ge} (get-in entity-manipulation-config [type :get-entities])]
    (if-not strategy
      (util/http-get (build-url domain type) token)
      (case strategy
        :fixed-list (let [{:keys [ids filter-out-404]} ge
                          entities (mapv #(get-entity {:domain domain
                                                       :type   type
                                                       :id     %
                                                       :token  token})
                                         ids)]
                      (if-not filter-out-404
                        entities
                        (into [] (filter #(not= 404 (:status-code %)) entities))))))))

(defn get-entities
  "Perform an Auth API get for all entities of `type`, using `token` for authz"
  [{:keys [domain type token entity-manipulation-config] :as args}]
  (if-not entity-manipulation-config
    (util/http-get (build-url domain type) token)
    (get-entities-using-strategy args)))

(defn snapshot
  "Take a snapshot of the current existing Auth0 entities for each type in `types`.

  This is useful for creating an initial configuration"
  [{:keys [domain types token entity-manipulation-config]
    :or   {types [:client :resource-server :connection :rule]}}]
  (reduce
   (fn [acc entity-type]
     (assoc acc entity-type (get-entities {:domain                     domain
                                           :type                       entity-type
                                           :token                      token
                                           :entity-manipulation-config (or entity-manipulation-config
                                                                           (-> "entity-manipulation-config.edn"
                                                                               io/resource
                                                                               slurp
                                                                               edn/read-string))})))
   {}
   types))

(defn delete-entity
  "Delete an entity, based on its type and id"
  [{:keys [domain type id token]}]
  (util/http-delete (format "%s/%s" (build-url domain type) id) token))
