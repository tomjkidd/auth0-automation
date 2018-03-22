# auth0-automation

A data driven way to manage an Auth0 tenant via configuration files

# Beta Warning

This is in development currently, and the readme is being updated with what should happen, not yet all of
it is supported. If you want to use this, you will have to read the source and find what is present and
missing accordingly.

## Motivation

While working with Auth0's dashboard with a team of any significant size, you will run into the problem
that the configuration of different entities may drift in ways that are hard to detect and control.

In order to allow source control to manage Auth0 configuration as a stable, versionable concept, this repo
seeks to make it easy to take an edn configuration file and allow you to consume that configuration to set
up and update an existing Auth0 environment.

## Disclaimers

This script is meant to be run by a single user with the assumption that they are the only one modifying the
environment. All bets are off if more than one user is accessing the Auth0 environment at once, and the value of
adding more functionality to support the detection of and resolution of conflicts is not the goal.

This library utilizes the Maintenance API of Auth0, and doesn't surface anything for the Authentication API.
All references to the Auth0 API in this repo should be assumed to mean the Maintenance API.

## Usage

This program relies on a few environment variables in order to work, so you must set them to run correctly

| Name | Type |  Description |
|:-----|:----:|:-------------|
| `AUTH0_AUTOMATION_DOMAIN` | String | Usually in the form of `<tenant>.auth0.com` |
| `AUTH0_AUTOMATION_CLIENT_ID` | String | The non-interactive `client-id`, with all scopes for entities you wish to change |
| `AUTH0_AUTOMATION_CLIENT_SECRET` | String | The secret that corresponds to `AUTH0_AUTOMATION_CLIENT_ID` |
| `AUTH0_AUTOMATION_EDN_CONFIG_FILEPATH` | String | The location to look for the `edn-config` to use, more about that below |

An `edn-config` file contains a vector of hash-maps that represent the state of the Auth0 enviornment you are
trying to setup.

Each hash-map in edn-config has `:type`, `:id-key`, `:search-key`, and `:payload` keys.

| Key | Type | Description |
|:----|:----:|:------------|
| :type | Keyword | One of `#{:client :resource-server :connection :user}` |
| :id-key | Keyword | Maps to the Auth0 generated id of an entity. This key is used to collect information to report to the user after the program runs to allow the user to verify updated entities. (These ids are typically used in other programs to connect to specific entities. |
| :search-key | Keyword | Represents the edn based identifier used to detect existing entities. It is used by the program to determine if an entity already exists. Because `:id-key` is not controlled by programmers, this provides a stable way to identify an entity.|
| :payload | edn | An edn data structure that will be transformed to a json payload to use as the body for either a POST/PUT to create/update an entity. |

```
NOTE: For most of the Auth0 API, there is a consistent mapping from entity type to url.
While not all types have yet been explicitly supported, the `build-url` multi-method
provides a fairly straight-forward way to add more. This repo is still beta, so take it with
a grain of salt.
```

```
NOTE: kebab-case keywords are used for `payload`, and will be converted to snake_case strings
```

This program will first consume the `edn-config`, get an Auth0 access-token, and sequentially process the
edn-config using calls to the Auth0 API to determine if entities exists, or if it needs to create them. The
program will create an intermediate data-structure, `api-actions` based on this information to communicate
the steps necessary to get from the current state to the desired state. Finally, api-actions is then consumed
to actually perform the the changes. The program captures the ids of the entities that it created and provides
them as edn output in order to allow the user to verify the work via the dashboard and/or to use the results
downstream to programmatically ensure other parts of the system are configured correctly.

Run the project directly:

    $ boot run

Run the repl to play around

    $ boot repl

```clojure
(require 'auth0-automation.repl)
(in-ns 'auth0-automation.repl)
(require '[clojure.pprint :refer [pprint]])
(def ec (get-entity-cache)) ;; Will make calls for known entity types and allow you to inspect them
(pprint (keys ec)) ;; See which entities are available, keyed by type, ie :client and :resource-server
(->> ec :client first pprint) ;; pprint the first client returned from the tenant
```

```clojure
(require '[auth0-automation.core :as core])
(require '[auth0-automation.auth0 :as auth0])
(require '[clojure.pprint :refer [pprint]])

(def token (auth0/get-token core/env-config))
(def domain (get-in core/env-config [:auth0 :domain]))
(def snapshot (auth0/snapshot {:domain domain :token token :types [:client :resource-server :connection :rule]}))
(spit "auth0-snapshot.edn" (with-out-str (pprint snapshot)))
```

Run the project's tests:

    $ boot test

Build an uberjar from the project:

    $ boot build

Run the uberjar:

    $ java -jar target/auth0-automation-0.1.0-SNAPSHOT-standalone.jar [args]

## Options

TODO: listing of options this app accepts.

## License

Copyright © 2018 Tom Kidd

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
