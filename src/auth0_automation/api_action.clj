(ns auth0-automation.api-action
  "The namespace responsible for the creation and manipulation of `api-actions`"
  (:require [auth0-automation.auth0 :as auth0]
            [clojure.data]))

(defmulti api-action
  "A hash-map with at least `:node-type`, `:msg`, and `edn-entity` keys

  Used to describe the api operations that need to be performed to create the desired
  Auth0 environment compared to the existing one."
  :node-type)

(defmethod api-action
  :create
  [{:keys [node-type edn-entity] :as node}]
  (let [{:keys [type search-key payload]} edn-entity]
    (assoc node :msg (format "Create %s: %s" (name type) (search-key payload)))))

(defmethod api-action
  :update
  [{:keys [node-type diff auth0-entity edn-entity] :as node}]
  (let [{:keys [type search-key payload]} edn-entity]
    (assoc node :msg (format "Update %s: %s" (name type) (search-key payload)))))

(defmethod api-action
  :noop
  [{:keys [node-type edn-entity] :as node}]
  (let [{:keys [type search-key payload]} edn-entity]
    (assoc node :msg (format "No-op %s: %s" (name type) (search-key payload)))))

(defn diff
  "Determine the diff between the two entities to figure out how to handle update."
  [auth0-entity edn-entity]
  (let [[auth0-versions edn-versions in-agreement] (clojure.data/diff auth0-entity (:payload edn-entity))]
    (when edn-versions
      {:auth0-version auth0-versions
       :edn-version edn-versions
       :in-agreement in-agreement})))

(defn determine-api-action
  "Calls out to the Auth0 api to get all existing entities, and tries to locate a
  match, based on the `:search-key` of the given `edn-entity`.

  If not found, a `:create` api-action is returned.
  If found, a diff is done to see if there are changes.
  When there are changes, a `:update` api-action is returned, otherwise a `:noop`"
  [{:keys [token domain]} {:keys [search-key payload type] :as edn-entity}]
  (let [auth0-entities (auth0/get-entities {:domain domain
                                            :type   type
                                            :token  token})
        auth0-entity (->> auth0-entities
                          (filter #(= (search-key payload) (search-key %)))
                          first)]
    (api-action (if auth0-entity
                  (if-let [d (diff auth0-entity edn-entity)]
                    {:node-type    :update
                     :diff         d
                     :auth0-entity auth0-entity
                     :edn-entity   edn-entity}
                    {:node-type    :noop
                     :auth0-entity auth0-entity
                     :edn-entity   edn-entity})
                  {:node-type  :create
                   :edn-entity edn-entity}))))

(defn determine-api-actions
  "Process `edn-config` to determine the `api-actions` that are necessary.

  `edn-config` is a vector of hash-maps that represent desired entities to create for the Auth0
  environment. This vector is processed sequentially to decide what to do for each.
  See `determine-api-action` for more details."
  [token edn-config env-config]
  (:api-actions (reduce (fn [acc edn-entity]
                          (update acc :api-actions conj (determine-api-action acc edn-entity)))
                        {:token       token
                         :domain      (get-in env-config [:auth0 :domain])
                         :api-actions []}
                        edn-config)))
