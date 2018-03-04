(ns auth0-automation.util
  (:require [camel-snake-kebab.core :refer [->snake_case ->kebab-case]]
            [cheshire.core :refer [generate-string parse-string]]
            [clj-http.client :as client]))

(defn serialize
  "Serialze `edn` into json"
  [edn]
  (generate-string edn {:key-fn (comp name ->snake_case)}))

(defn deserialize
  "Deserialze `json` into kebab-case keyed edn"
  [json]
  (parse-string json (comp keyword ->kebab-case)))

(defn http-get
  "Perform and HTTP get at `url`, using `token` for authz.
  Assumes a json response, and converts it to kebab-case key edn"
  [url token]
  (-> url
      (client/get {:headers {"Authorization" (format "Bearer %s" token)}})
      :body
      deserialize))
