(ns auth0-automation.core
  (:require [auth0-automation.api-action :as api-action]
            [auth0-automation.auth0 :as auth0]
            [auth0-automation.util :as util]
            [clojure.pprint :refer [pprint]]
            [environ.core :refer [env]]
            [io.pedestal.interceptor.chain :as interceptor-chain])
  (:gen-class))

(def exception-interceptor
  "If an unexpected exception occurs during the process, capture it in `::exception`.

  Will also print it to console if `suppress-output?` is not true"
  {:error (fn exception-catcher [{:keys [suppress-output? verbose?] :as ctx} ex]
            (when-not suppress-output?
              (println "Unexpected exception occured.")
              (if verbose?
                (pprint {:ctx ctx
                         :ex  ex})
                (pprint ex)))
            (assoc ctx ::exception (ex-data ex)))})

(defn- display-error-msg
  [{:keys [msg]}]
  (when msg
    (println "Error msg: " msg)))

(def errors-interceptor
  "Ensures that this program's errors can be defined and respected as part of the interceptor chain.
  If any expected errors occurs during the process, capture them in `::errors`.

  Will also print it to console if `suppress-output?` is not true"
  {:enter (fn register-error-terminator [ctx]
            (interceptor-chain/terminate-when ctx ::errors))
   :leave (fn report-error [{:keys [suppress-output? verbose?] :as ctx}]
            (when-not suppress-output?
              (when-let [errs (::errors ctx)]
                (println "Error(s) occured.")
                (doseq [err errs]
                  (display-error-msg err))
                (when verbose?
                  (pprint ctx))))
            ctx)})

(def abort-interceptor
  "Ensure that the case where a user decides to quit can be handled.
  If at any decision point the user chooses to abort, capture it in `::abort`."
  {:enter (fn register-abort-terminator [ctx]
            (interceptor-chain/terminate-when ctx ::abort))
   :leave (fn report-abort [{:keys [suppress-output? verbose?] :as ctx}]
            (when-not suppress-output?
              (when-let [{:keys [msg]} (::abort ctx)]
                (println "Abort occured.")
                (println msg)
                (when verbose?
                  (pprint ctx))))
            ctx)})

(def env-config
  "A convenient representation of the environment variables that are needed"
  {:auth0      {:domain        (env :auth0-automation-domain)
                :client-id     (env :auth0-automation-client-id)
                :client-secret (env :auth0-automation-client-secret)}
   :edn-config {:filepath (env :auth0-automation-edn-config-filepath)}})

(def provide-env-config
  "Adds `env-config` to the context."
  {:enter (fn [ctx]
            (assoc ctx :env-config env-config))})

(def read-edn-configuration
  "Reads the `edn-configuration` file that describes the desired Auth0 environment"
  {:enter (fn [{:keys [env-config] :as ctx}]
            (if-let [edn-config (util/load-edn-config env-config)]
              (assoc ctx :edn-config edn-config)
              (assoc ctx ::errors [{:type :edn-config-does-not-exist
                                    :msg  "Was unable to locate an edn-config file. Make sure the filepath points to an actual file."}])))})

(def get-auth0-token
  "Attempts to get an Auth0 Management API token"
  {:enter (fn [{:keys [dryrun? env-config] :as ctx}]
            (if dryrun?
              ctx
              (if-let [token (auth0/get-token env-config)]
                (assoc ctx :auth0-token token)
                (assoc ctx ::errors [{:type :unable-to-get-auth0-token
                                      :msg  "Unable to get Auth0 token. Make sure that the domain, client-id, and client-secret are correct and that the Auth0 service is working."}]))))})

(def determine-api-actions
  "Create a data structure that represents the actions that are needed to create
  the desired Auth0 environment based on the current one."
  {:enter (fn [{:keys [auth0-token edn-config env-config] :as ctx}]
            (assoc ctx :api-actions (api-action/determine-api-actions auth0-token edn-config env-config)))})

(def confirm-api-actions
  ""
  {:enter (fn [{:keys [interactive? suppress-output? api-actions] :as ctx}]
            (if (and interactive? (not suppress-output?))
              (do
                ;;TODO: Use prompt user for if they'd like to continue.
                ;; Use api-actions to allow the user to make the decision.
                (doseq [{:keys [msg]} api-actions]
                  (println msg))
                (let [continue? true]
                  (if continue?
                    ctx
                    (assoc ctx ::abort {:msg "The user decided to quit."}))))
              ctx))})

(def transact-api-actions!
  "Performs the actions identified by `::api-actions` against the Auth0 environment"
  {:enter (fn [{:keys [dryrun?] :as ctx}]
            (if dryrun?
              ctx
              (assoc ctx ::api-call-responses [])))})

(def report-results
  {:enter (fn [{:keys [suppress-output? verbose? dryrun? api-actions] :as ctx}]
            (if-not suppress-output?
              (do
                (when-not (or dryrun? (::errors ctx) (::abort ctx))
                  (println "Successfully configured the Auth0 environment"))
                (when verbose?
                  (pprint (dissoc ctx
                                  :io.pedestal.interceptor.chain/execution-id
                                  :io.pedestal.interceptor.chain/queue
                                  :io.pedestal.interceptor.chain/stack
                                  :io.pedestal.interceptor.chain/terminators))))
              (pprint api-actions))
            ctx)})

(def interceptor-pipeline
  "The pipeline of interceptors used to perform the Auth0 environment update"
  [exception-interceptor
   errors-interceptor
   abort-interceptor
   provide-env-config
   read-edn-configuration
   get-auth0-token
   determine-api-actions
   confirm-api-actions
   transact-api-actions!
   report-results])

(defn run
  "Perform the Auth0 environment update to match the desired configuration"
  ([opts]
   (run opts interceptor-pipeline))
  ([opts pipeline]
   (interceptor-chain/execute opts pipeline)))

(defn args->opts
  "Turn command-line arguments into an opts hashmap, used to control program behavior"
  [args]
  ;;TODO: Pay attention to args to create options map
  {:interactive?     true
   :verbose?         false
   :suppress-output? true})

(defn -main
  "This program assumes no other users are changing the current Auth0 environment,
  and makes no attempt to detect simultaneous use. Make sure no one else is updating
  the environment while running this script!"
  [& args]
  (run (args->opts args)))
