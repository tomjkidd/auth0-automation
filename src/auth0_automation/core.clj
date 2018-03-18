(ns auth0-automation.core
  (:require [auth0-automation.api-action :as api-action]
            [auth0-automation.auth0 :as auth0]
            [auth0-automation.util :as util]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [environ.core :refer [env]]
            [io.pedestal.interceptor.chain :as interceptor-chain])
  (:gen-class))

(def EXCEPTION 3)
(def ERRORS 2)
(def ABORT 1)
(def SUCCESS 0)

(defn determine-exit-code
  "A function to use the result interceptor context to determine an exit-code."
  [ctx]
  (cond
    (::exception ctx) EXCEPTION
    (::errors ctx)    ERRORS
    (::abort ctx)     ABORT
    :else             SUCCESS))

(def exception-interceptor
  "If an unexpected exception occurs during the process, capture it in `::exception`."
  {:error (fn exception-catcher [ctx ex]
            (assoc ctx ::exception (ex-data ex)))})

(def errors-interceptor
  "Ensures that this program's errors can be defined and respected as part of the interceptor chain.
  If any expected errors occurs during the process, capture them in `::errors`."
  {:enter (fn register-error-terminator [ctx]
            (interceptor-chain/terminate-when ctx ::errors))})

(def abort-interceptor
  "Ensure that the case where a user decides to quit can be handled.
  If at any decision point the user chooses to abort, capture it in `::abort`."
  {:enter (fn register-abort-terminator [ctx]
            (interceptor-chain/terminate-when ctx ::abort))})

(def env-config
  "A convenient representation of the environment variables that are needed"
  {:auth0      {:domain        (env :auth0-automation-domain)
                :client-id     (env :auth0-automation-client-id)
                :client-secret (env :auth0-automation-client-secret)}
   :edn-config {:filepath (env :auth0-automation-edn-config-filepath)}})

(def ensure-env-config
  "Adds `env-config` to the context.

  If passed in through `opts`, use that, otherwise use the environment variables."
  {:enter (fn [{:keys [opts] :as ctx}]
            (assoc ctx :env-config (or (:env-config opts) env-config)))})

(def ensure-edn-configuration
  "Reads the `edn-configuration` file that describes the desired Auth0 environment

  If passed in through `opts`, use that, otherwise attempt to load file using environment variable."
  {:enter (fn [{:keys [opts env-config] :as ctx}]
            (let [{:keys [edn-config]} opts]
              (if edn-config
                (assoc ctx :edn-config edn-config)
                (if-let [edn-config (util/load-edn-config env-config)]
                  (assoc ctx :edn-config edn-config)
                  (assoc ctx ::errors [{:type :edn-config-does-not-exist
                                        :msg  "Was unable to locate an edn-config file. Make sure the filepath points to an actual file."}])))))})

(def get-auth0-token
  "Attempts to get an Auth0 Management API token"
  {:enter (fn [{:keys [env-config] :as ctx}]
            (if-let [token (auth0/get-token env-config)]
              (assoc ctx :auth0-token token)
              (assoc ctx ::errors [{:type :unable-to-get-auth0-token
                                    :msg  "Unable to get Auth0 token. Make sure that the domain, client-id, and client-secret are correct and that the Auth0 service is working."}])))})

(def determine-api-actions
  "Create a data structure that represents the actions that are needed to create
  the desired Auth0 environment based on the current one."
  {:enter (fn [{:keys [auth0-token edn-config env-config] :as ctx}]
            (assoc ctx :api-actions (api-action/determine-api-actions auth0-token edn-config env-config)))})

(def confirm-api-actions
  "When `:interactive?` is true, confirm `api-actions` with user, giving them a chance to abort."
  {:enter (fn [{:keys [opts api-actions] :as ctx}]
            (let [{:keys [interactive? suppress-output?]} opts]
              (if (and interactive? (not suppress-output?))
                (do
                  ;; Prompt user for if they'd like to continue,
                  ;; using api-actions to allow the user to make the decision.
                  ;; TODO: Remove exception/error triggers
                  (doseq [{:keys [msg]} api-actions]
                    (println msg))
                  (let [_         (println "Would you like to continue?")
                        response  (read-line)
                        _ (when (= response "t") (throw (ex-info "Test exception" {:reason "test exceptions"})))
                        error-ctx (when (= response "e") (assoc ctx ::errors [{:msg "test errors"}]))
                        continue? (contains? #{"y" "yes"} (string/lower-case response))]
                    (if continue?
                      ctx
                      (if error-ctx
                        error-ctx
                        (assoc ctx ::abort {:msg "The user decided to quit."})))))
                ctx)))})

(def transact-api-actions!
  "Performs the actions identified by `::api-actions` against the Auth0 environment"
  {:enter (fn [{:keys [opts auth0-token api-actions env-config] :as ctx}]
            (let [{:keys [dry-run?]} opts]
              (if dry-run?
                ctx
                (let [api-responses (api-action/transact-api-actions! auth0-token api-actions env-config)]
                  ;; TODO: Detect errors and add them to `::errors`
                  (assoc ctx :api-responses api-responses)))))})


(defn- display-error-msg
  [{:keys [msg]}]
  (when msg
    (println "Error msg: " msg)))

(defn report-results
  "Use the exit-code, ::exception, ::errors, and ::abort to determine how to communicate results to the user."
  [{:keys [opts] :as ctx}]
  (let [{:keys [suppress-output? verbose? dry-run?]} opts
        exit-code (determine-exit-code ctx)
        verbose-ctx                                  (dissoc ctx
                                                             :io.pedestal.interceptor.chain/execution-id
                                                             :io.pedestal.interceptor.chain/queue
                                                             :io.pedestal.interceptor.chain/stack
                                                             :io.pedestal.interceptor.chain/terminators)]
    (cond
      (= EXCEPTION exit-code)
      (let [ex (::exception ctx)]
        (when-not suppress-output?
          (if verbose?
            (pprint {:ctx verbose-ctx
                     :ex  ex})
            (pprint ex))
          (println "Unexpected exception occured.")))

      (= ERRORS exit-code)
      (let [errors (::errors ctx)]
        (when-not suppress-output?
          (when verbose?
            (pprint verbose-ctx))
          (println "Error(s) occured.")
          (doseq [err errors]
            (display-error-msg err))))

      (= ABORT exit-code)
      (let [{:keys [msg]} (::abort ctx)]
        (when-not suppress-output?
          (when verbose?
            (pprint verbose-ctx))
          (println "Abort occured.")
          (println msg)))

      :else
      (when-not suppress-output?
        (do
          (when verbose?
            (pprint verbose-ctx))

          (if dry-run?
            (do
              (pprint (:api-actions ctx))
              (println "Dry run: api-actions that would be performed are displayed above"))
            (println "Successfully configured the Auth0 environment"))))))
  ctx)

(def interceptor-pipeline
  "The pipeline of interceptors used to perform the Auth0 environment update"
  [exception-interceptor
   errors-interceptor
   abort-interceptor
   ensure-env-config
   ensure-edn-configuration
   get-auth0-token
   determine-api-actions
   confirm-api-actions
   transact-api-actions!])

(defn run-pipeline
  "Perform the Auth0 environment update to match the desired configuration"
  ([opts]
   (run-pipeline opts interceptor-pipeline))
  ([opts pipeline]
   (interceptor-chain/execute {:opts opts} pipeline)))

(defn run
  "Run the Auth0 environment update, report results, and exit."
  [opts]
  (let [result (run-pipeline opts)]
    (report-results result)
    (System/exit (determine-exit-code result))))

(def cli-options
  "The `parse-opts` cli options to provide the program"
  [["-v" "--verbose" "Pretty-print the entire context of the program as part of the program output"
    :default false]
   ["-i" "--interactive" "Allow the user to see the proposed `api-actions` and decide to abort."
    :default false]
   ["-d" "--dryrun" "Allow the user to see the proposed `api-actions` without actually performing them."
    :default false]
   ;; TODO: Enable this feature
   #_["-f" "--output-format" "Output data format, one of #{\"HR\" \"EDN\"}. HR is human readable with summary messages, while EDN puts the messages and output into an edn data structure, so it can be used as input to another program. Default is EDN."
    :default "EDN"]
   ["-h" "--help" "Print out usage"]])

(defn args->opts
  "Turn command-line arguments into an opts hashmap, used to control program behavior"
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        action (cond
                 (pos? (count errors)) :help
                 (:help options) :help
                 :else (first arguments))]
    (-> options
        (update :output-format #(-> % string/upper-case keyword))
        (set/rename-keys {:verbose :verbose?
                          :interactive :interactive?
                          :dryrun :dry-run?})
        (assoc :action action :summary summary))))

(defn usage
  "Print out the usage for run"
  [options-summary]
  (->> ["auth0-automation: Turns an edn config file into an Auth0 environment."
        ""
        "Usage:"
        "boot run --args=\"[options]\" [action]"
        "java -jar auth0-automation [options] [action]"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  help     Prints this usage"]
       (string/join \newline)))

(defn -main
  "This program assumes no other users are changing the current Auth0 environment,
  and makes no attempt to detect simultaneous use. Make sure no one else is updating
  the environment while running this script!"
  [& args]
  (let [{:keys [action summary] :as opts} (args->opts args)]
    (pprint opts)
    (if (not= :help action)
      (run opts)
      (println (usage summary)))))
