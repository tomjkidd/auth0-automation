(ns auth0-automation.auth0
  (:require [auth0-automation.util :as util]
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

(defn get-entities
  "Perform an Auth API get for all entities of `type`, using `token` for authz"
  [{:keys [domain type token]}]
  (util/http-get (build-url domain type) token))

(defn get-entity
  "Perform an Auth API get for the specific entity of `type` identified by `id`, using `token` for authz"
  [{:keys [domain type id token]}]
  (util/http-get (format "%s/%s" (build-url domain type) id) token))
