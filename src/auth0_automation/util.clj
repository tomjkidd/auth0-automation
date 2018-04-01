(ns auth0-automation.util
  (:require [camel-snake-kebab.core :refer [->snake_case ->kebab-case]]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clj-http.client :as client]))

(defn dissoc-in
  "Perform a dissoc into a nested structure, with the same semantics as assoc-in"
  [m [k & ks]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (assoc m k newmap))
      m)
    (dissoc m k)))

(defn serialize
  "Serialze `edn` into json"
  [edn]
  (generate-string edn {:key-fn (comp name ->snake_case)}))

(defn deserialize
  "Deserialze `json` into kebab-case keyed edn"
  [json]
  (parse-string json (comp keyword ->kebab-case)))

(defn http-helper
  "A helper that captures what is common to all http requests

  NOTE: The Auth0 http requests return status codes in their bodies on failure,
  and this is relied on, at least for the moment."
  [http-fn {:keys [url body token]}]
  (-> (http-fn url (cond-> {:headers          {"Authorization" (format "Bearer %s" token)}
                            :accept           :json
                            :throw-exceptions false}
                     body (merge {:content-type     :json
                                  :body             (serialize body)})))
      :body
      deserialize))

(defn http-get
  "Perform and HTTP get at `url`, using `token` for authz.
  Assumes a json response, and converts it to kebab-case key edn"
  [url token]
  (http-helper client/get {:url url :token token}))

(defn http-post
  "Perform an HTTP post at `url`, with `body`, using `token` for authz.
  Assumes a json response, and converts it to kebab-case key edn"
  [url body token]
  (http-helper client/post {:url url :body body :token token}))

(defn http-patch
  "Perform an HTTP patch at `url`, with `body`, using `token` for authz.
  Assumes a json response, and converts it to kebab-case key edn"
  [url body token]
  (http-helper client/patch {:url url :body body :token token}))

(defn load-edn-config
  "Use the `env-config` to locate the `edn-config` filepath, and read it in"
  [env-config]
  (let [filepath (get-in env-config [:edn-config :filepath])]
    (when (and (some? filepath) (.exists (io/as-file filepath)))
      (edn/read-string (slurp filepath)))))
