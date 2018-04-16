(ns auth0-automation.api-action
  "The namespace responsible for the creation and manipulation of `api-actions`"
  (:require [auth0-automation.auth0 :as auth0]
            [auth0-automation.util :as util]
            [clojure.data]))

(defmulti api-action
  "A hash-map with at least `:node-type`, `:msg`, and `edn-config-entry` keys

  Used to describe the api operations that need to be performed to create the desired
  Auth0 environment compared to the existing one."
  :node-type)

(defmethod api-action
  :create
  [{:keys [node-type edn-config-entry] :as node}]
  (let [{:keys [entity-type search-key payload]} edn-config-entry]
    (assoc node :msg (format "Create %s: %s" (name entity-type) (if (keyword? search-key)
                                                                  (search-key payload)
                                                                  search-key)))))

(defmethod api-action
  :update
  [{:keys [node-type diff auth0-entity edn-config-entry] :as node}]
  (let [{:keys [entity-type search-key payload]} edn-config-entry]
    (assoc node :msg (format "Update %s: %s" (name entity-type) (if (keyword? search-key)
                                                                  (search-key payload)
                                                                  search-key)))))

(defmethod api-action
  :noop
  [{:keys [node-type edn-config-entry] :as node}]
  (let [{:keys [entity-type search-key payload]} edn-config-entry]
    (assoc node :msg (format "No-op %s: %s" (name entity-type) (if (keyword? search-key)
                                                                 (search-key payload)
                                                                 search-key)))))

(defmethod api-action
  :ref-dep
  [{:keys [node-type edn-config-entry] :as node}]
  (let [{:keys [entity-type search-key search-value dependency-key dependency-value]} edn-config-entry]
    (assoc node :msg (format "Referential Dependency for %s: Will look for key %s with value %s (of type %s) and set %s to %s (of type %s)"
                             (name entity-type)
                             search-key
                             search-value
                             (type search-value)
                             dependency-key
                             dependency-value
                             (type dependency-value)))))

(defn diff
  "Determine the diff between the two entities to figure out how to handle update."
  [auth0-entity edn-entity]
  (let [[auth0-versions edn-versions in-agreement] (clojure.data/diff auth0-entity (:payload edn-entity))]
    (when edn-versions
      {:auth0-version auth0-versions
       :edn-version edn-versions
       :in-agreement in-agreement})))

(defn find-auth0-entity
  "Loads all entities and attempts to locate the the first where `search-key` is `value`"
  [{:keys [token domain entity-manipulation-config]} {:keys [search-key search-value entity-type]}]
  (let [auth0-entities (auth0/get-entities {:domain domain
                                            :type   entity-type
                                            :token  token
                                            :entity-manipulation-config entity-manipulation-config})]
    (->> auth0-entities
         (filter #(= search-value (search-key %)))
         first)))

(defn client-grant-find-dependencies
  [{:keys [token domain entity-manipulation-config]} {:keys [search-key payload entity-type] :as edn-config-entry}]
  (let [args             {:domain                     domain
                          :type                       entity-type
                          :token                      token
                          :entity-manipulation-config entity-manipulation-config}
        clients          (auth0/get-entities (assoc args :type :client))
        resource-servers (auth0/get-entities (assoc args :type :resource-server))
        client-name->id (reduce (fn [acc {:keys [client-id name]}] (assoc acc name client-id))
                                {}
                                clients)
        resource-server-name->id (reduce (fn [acc {:keys [id name identifier]}] (assoc acc name {:id         id
                                                                                                 :identifier identifier}))
                                         {}
                                         resource-servers)
        client-id (client-name->id (:client-name payload))
        {:keys [id identifier]} (resource-server-name->id (:resource-server-name payload))]
    {:client-id                  client-id
     :resource-server-id         id
     :resource-server-identifier identifier}))

(defn to-api-action
  "Take an edn-config-entry and an auth0-entity and return an api-action"
  [edn-config-entry auth0-entity]
  (api-action (if auth0-entity
                (if-let [d (diff auth0-entity edn-config-entry)]
                  {:node-type        :update
                   :diff             d
                   :auth0-entity     auth0-entity
                   :edn-config-entry edn-config-entry}
                  {:node-type        :noop
                   :auth0-entity     auth0-entity
                   :edn-config-entry edn-config-entry})
                {:node-type        :create
                 :edn-config-entry edn-config-entry})))

(defmulti determine-entity-api-action
  "Calls out to the Auth0 api to get all existing entities, and tries to locate a
  match, based on the `:search-key` of the given `edn-entity`.

  If not found, a `:create` api-action is returned.
  If found, a diff is done to see if there are changes.
  When there are changes, a `:update` api-action is returned, otherwise a `:noop`

  This multimethod can be extended for client types to support entities that deviate from default use case"
  (fn [_ {:keys [entity-type]}] entity-type))

(defmethod determine-entity-api-action :client-grant
  [{:keys [token domain entity-manipulation-config]} {:keys [payload entity-type] :as edn-config-entry}]
  ;; NOTE: The basic algorithm is that we are given names of a client and resource-server, and we have to provide
  ;; a way to see if an client-grant already exists that is configured with those names.
  (let [args {:domain                     domain
              :type                       entity-type
              :token                      token
              :entity-manipulation-config entity-manipulation-config}
        auth0-entities (auth0/get-entities (assoc args :type entity-type))
        clients (auth0/get-entities (assoc args :type :client))
        resource-servers (auth0/get-entities (assoc args :type :resource-server))
        client-name->id (reduce (fn [acc {:keys [client-id name]}] (assoc acc name client-id))
                                {}
                                clients)
        resource-server-name->identifier (reduce (fn [acc {:keys [name identifier]}] (assoc acc name identifier))
                                         {}
                                         resource-servers)
        client-id (client-name->id (:client-name payload))
        resource-server-identifier (resource-server-name->identifier (:resource-server-name payload))
        match-fn #(and (= (:client-id %) client-id)
                       (= (:audience %) resource-server-identifier))
        auth0-entity (when (and client-id resource-server-identifier)
                       (->> auth0-entities
                            (filter match-fn)
                            first))
        ;; NOTE: client-id and audience are not able to be updated, only :scope can change
        update-edn-config-entry (when auth0-entity
                                  (update edn-config-entry :payload select-keys [:scope]))]
    (to-api-action update-edn-config-entry auth0-entity)))

(defmethod determine-entity-api-action :default
  [acc {:keys [search-key payload] :as edn-config-entry}]
  (let [auth0-entity (find-auth0-entity acc (assoc edn-config-entry :search-value (search-key payload)))]
    (to-api-action edn-config-entry auth0-entity)))

(defmulti determine-api-action
  "A hash-map used to translate a desired edn-config-entry to an api-action

  :entity api actions are ones where payloads are used to create or edit entities
  :referential-dependency api-actions are ones for existing entities to refer to other entities"
  (fn [acc {:keys [type]}] type))

(defmethod determine-api-action
  :entity
  [acc edn-config-entry]
  (determine-entity-api-action acc edn-config-entry))

(defmethod determine-api-action
  :referential-dependency
  [acc edn-config-entry]
  ;; Assumes that the command will run after all entities it depends on, may want to do a check
  (api-action {:node-type :ref-dep
               :edn-config-entry edn-config-entry}))

(defn determine-api-actions
  "Process `edn-config` to determine the `api-actions` that are necessary.

  `edn-config` is a vector of hash-maps that represent desired entities to create for the Auth0
  environment. This vector is processed sequentially to decide what to do for each.
  See `determine-api-action` for more details."
  [token edn-config env-config entity-manipulation-config]
  (:api-actions
   (reduce (fn [acc edn-config-entry]
             (update acc :api-actions conj (determine-api-action acc edn-config-entry)))
           {:token       token
            :domain      (get-in env-config [:auth0 :domain])
            :entity-manipulation-config entity-manipulation-config
            :api-actions []}
           edn-config)))

(defn payload-dissoc
  "Return a map, dissocing from it by keyword for top-level, or path for nested.

  `dissoc-keys-or-paths` is a list of keywords or vectors of keywords.
  In the case of keywords, they are removed directly from top level using dissoc.
  In the case of a vector of keywords, same semantics as assoc-in apply for dissoc."
  [m dissoc-keys-or-paths]
  (reduce (fn [acc k-or-p]
            (if (keyword? k-or-p)
              (dissoc acc k-or-p)
              (util/dissoc-in acc k-or-p)))
          m
          dissoc-keys-or-paths))

(defmulti transact-entity-api-action! (fn [_ {:keys [edn-config-entry]}] (:entity-type edn-config-entry)))

(defmethod transact-entity-api-action! :client-grant
  [{:keys [token domain entity-manipulation-config] :as acc} {:keys [node-type auth0-entity edn-config-entry]}]
  (let [{:keys [entity-type id-key payload]} edn-config-entry

        base-url (auth0/build-url domain entity-type)

        {:keys [dissoc-for-create dissoc-for-update]}
        (get entity-manipulation-config entity-type)

        ;; TODO: This assumes that the client and resource server were created before this client-grant!
        {:keys [client-id resource-server-identifier]} (client-grant-find-dependencies acc edn-config-entry)

        {:keys [url body transact-fn]}
        (case node-type
          :create  {:url         base-url
                    :body        (-> payload
                                     (payload-dissoc dissoc-for-create)
                                     (assoc :client-id client-id :audience resource-server-identifier))
                    :transact-fn util/http-post}
          ;; The only properties are client-id and audience, and you can only update scopes
          :update  {:url         (format "%s/%s" base-url (id-key auth0-entity))
                    :body        (-> payload
                                     (payload-dissoc dissoc-for-update)
                                     (assoc :client-id client-id :audience resource-server-identifier))
                    :transact-fn util/http-patch}
          :noop    {:transact-fn (constantly nil)})]
    (transact-fn url body token)))

(defmethod transact-entity-api-action! :default
  [{:keys [token domain entity-manipulation-config]} {:keys [node-type auth0-entity edn-config-entry]}]
  (let [{:keys [entity-type id-key payload]} edn-config-entry

        base-url (auth0/build-url domain entity-type)

        {:keys [dissoc-for-create dissoc-for-update]}
        (get entity-manipulation-config entity-type)

        {:keys [url body transact-fn]}
        (case node-type
          :create  {:url         base-url
                    :body        (payload-dissoc payload dissoc-for-create)
                    :transact-fn util/http-post}
          :update  {:url         (format "%s/%s" base-url (id-key auth0-entity))
                    :body        (payload-dissoc payload dissoc-for-update)
                    :transact-fn util/http-patch}
          :noop    {:transact-fn (constantly nil)})]
    (transact-fn url body token)))

(defmulti transact-api-action! (fn [_ {:keys [edn-config-entry]}] (:type edn-config-entry)))

(defmethod transact-api-action! :entity
  [acc api-action]
  (transact-entity-api-action! acc api-action))

(defmethod transact-api-action! :referential-dependency
  [{:keys [token domain entity-manipulation-config] :as acc} {:keys [node-type edn-config-entry]}]
  (let [{:keys [entity-type id-key
                search-key search-value dependency-entity-type dependency-key dependency-value]} edn-config-entry

        base-url (auth0/build-url domain entity-type)

        manipulation-config (get entity-manipulation-config entity-type)

        {:keys [url body transact-fn]}
        (case node-type
          ;; NOTE: The ref-dep finds the entity at runtime, and it SHOULD exist.
          ;; TODO: We don't do rollback at this point, but this may throw like any other api call
          ;; TODO: This could be more efficient by having a step that gets all ids and correlates
          ;; them to the search-value so that we don't lookup every time.
          :ref-dep (let [dependency-entities    (auth0/get-entities {:domain domain
                                                                     :type   dependency-entity-type
                                                                     :token  token})
                         dep-search-key->dep-id (reduce (fn [acc cur]
                                                          (assoc acc (search-key cur) (id-key cur)))
                                                        {}
                                                        dependency-entities)
                         auth0-entity           (find-auth0-entity acc
                                                                   {:search-key   search-key
                                                                    :entity-type  entity-type
                                                                    :search-value search-value})

                         dependency-value (mapv #(dep-search-key->dep-id %) dependency-value)]
                     {:url         (format "%s/%s" base-url (get auth0-entity (:id-key manipulation-config)))
                      :body        {dependency-key dependency-value}
                      :transact-fn util/http-patch}))]
    (transact-fn url body token)))

(defn transact-api-actions!
  [token api-actions env-config entity-manipulation-config]
  (:api-responses
   (reduce (fn [acc api-action]
             (update acc :api-responses conj (transact-api-action! acc api-action)))
           {:token                      token
            :domain                     (get-in env-config [:auth0 :domain])
            :entity-manipulation-config entity-manipulation-config
            :api-responses              []}
           api-actions)))
