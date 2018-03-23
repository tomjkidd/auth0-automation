(ns auth0-automation.api-action
  "The namespace responsible for the creation and manipulation of `api-actions`"
  (:require [auth0-automation.auth0 :as auth0]
            [auth0-automation.util :as util]
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
  (:api-actions
   (reduce (fn [acc edn-entity]
             (update acc :api-actions conj (determine-api-action acc edn-entity)))
           {:token       token
            :domain      (get-in env-config [:auth0 :domain])
            :api-actions []}
           edn-config)))

(def default-payload-manipulation-config
  "A map from entity type keys to values needed to create or update type.

  This is needed because the payloads is assumed to be acquired from a GET request initially,
  and the create and update actions don't accept all of the same keys."
  {:client
   {:dissoc-for-create [:tenant :client-id :callback-url-template :global :owners :config-route]}

   :resource-server
   {:dissoc-for-create [:id]}

   :connection
   {:dissoc-for-create [:id]
    :dissoc-for-update [:id :name :strategy]}

   :rule
   {:dissoc-for-create [:id]
    :dissoc-for-update [:id :stage]}})

(defn transact-api-action!
  [{:keys [token domain]} {:keys [node-type auth0-entity edn-entity]}]
  (let [{:keys [type id-key payload]} edn-entity
        base-url (auth0/build-url domain type)
        {:keys [dissoc-for-create dissoc-for-update]} (type default-payload-manipulation-config)

        {:keys [url body transact-fn]}
        (case node-type
          :create {:url base-url
                   :body (apply dissoc payload dissoc-for-create)
                   :transact-fn util/http-post}
          :update {:url (format "%s/%s" base-url (id-key auth0-entity))
                   :body (apply dissoc payload dissoc-for-update)
                   :transact-fn util/http-patch}
          :noop {:transact-fn (constantly nil)})]
    (transact-fn url body token)))

(defn transact-api-actions!
  [token api-actions env-config]
  (:api-responses
   (reduce (fn [acc api-action]
             (update acc :api-responses conj (transact-api-action! acc api-action)))
           {:token         token
            :domain        (get-in env-config [:auth0 :domain])
            :api-responses []}
           api-actions)))
