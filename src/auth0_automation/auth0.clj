(ns auth0-automation.auth0
  (:require [camel-snake-kebab.core :refer [->snake_case ->kebab-case]]
            [cheshire.core :refer [generate-string parse-string]]
            [clj-http.client :as client]))

(def api-url-mappings
  {:audience "https://%s/api/v2/"
   :token    "https://%s/oauth/token"})

(defn build-url
  [api-url-key & args]
  (apply format (api-url-key api-url-mappings) args))

(defn get-token-response
  "Manages the post to Auth0 to get an `access_token`"
  [{:keys [auth0]}]
  (let [{:keys [domain client-id client-secret]} auth0

        url       (build-url :token domain)
        body      {:grant-type    "client_credentials"
                   :client-id     client-id
                   :client-secret client-secret
                   :audience      (build-url :audience domain)}
        json-body (generate-string
                   body
                   {:key-fn (comp name ->snake_case)})]
    (client/post url
                 {:content-type     :json
                  :body             json-body
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
          (parse-string (comp keyword ->kebab-case))
          :access-token))))
