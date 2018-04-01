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

This library determines actions to perform, and then attempts them. It is able to detect exceptions and errors,
but if errors occur during the transact phase there is no rollback strategy. Take a snapshot of the environment
in case you have to do any recovery!

## Progam Summary

This program will first read in a config file called `edn-config`, get an Auth0 access-token, and sequentially
process each `edn-config-entry` to create a corresponding `api-action`. This vector of `api-actions` is an
intermediate data-structure that contains the complete information necessary to get from the current Auth0
environment state to the desired one. In interactive mode, you can use this step to verify what will occur in a
readable format. Finally, `api-actions` are consumed to actually perform the changes. The output is captured,
and contains the ids of the entities involved, as well as other information, as edn. This allows the user to
verify the work via the Auth0 Dashboard, and/or to use the results downstream to programatically ensure other
parts of the system are configured correctly.

## Concepts and Details

This program relies on a few environment variables in order to work, so you must set them to run correctly

| Name | Type |  Description |
|:-----|:----:|:-------------|
| `AUTH0_AUTOMATION_DOMAIN` | String | Usually in the form of `<tenant>.auth0.com` |
| `AUTH0_AUTOMATION_CLIENT_ID` | String | The non-interactive `client-id`, with all scopes for entities you wish to change |
| `AUTH0_AUTOMATION_CLIENT_SECRET` | String | The secret that corresponds to `AUTH0_AUTOMATION_CLIENT_ID` |
| `AUTH0_AUTOMATION_EDN_CONFIG_FILEPATH` | String | The location to look for the `edn-config` to use, more about that below |


## edn-config

An `edn-config` file contains a vector of `edn-config-entry` hash-maps, and represents the sequential series of
steps that are needed to create an Auth0 environment.

### edn-config-entry types

There are two types of `edn-config-entry`, `:entity` and `:referential-dependency`

An `:entity` edn-config-entry is used to create or update an auth0 entity, setting most of it's parameters in
a simple fashion.

A `:referential-dependency` edn-config-entry is used to associate one Auth0 entity with a homogeneous list of
other Auth0 entities. These entries will only work if they follow the entity entries in the edn-config so that
the entities exist, so it is best to put all of the `:entity` entries first, followed by all of the
`:referential-dependency` entries.

For these relationships, using an `:entity` would be possible if you could control the id, but not all Auth0
entities allow an admin to configure the ids. Because of this, a different key, usually a `name`, is used to
reference entities.

#### :entity edn-config-entry

Each `:entity` hash-map in edn-config has the following keys and values:

| Key | Type | Description |
|:----|:----:|:------------|
| :type | Keyword | The value `:entity` is used to identify the edn-config-entry type |
| :entity-type | Keyword | The auth0 entity type. Effort was made to not have to code out all types, so see the source code for more details. One of `#{:client :resource-server :connection :user :rule}` are common values. |
| :id-key | Keyword | Maps to the Auth0 generated id of an entity. This key is used to collect information to report to the user after the program runs to allow the user to verify updated entities. (These ids are typically used in other programs to connect to specific entities.) |
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

#### :referential-dependency edn-config-entry

Each `:referential-dependency` hash-map in edn-config has the following keys and values:

| Key | Type | Description |
|:----|:----:|:------------|
| :type | Keyword | The value `:referential-dependency` is used to identify the edn-config-entry type |
| :entity-type | Keyword | The auth0 entity type that has a referential-dependency |
| :dependency-value | List of strings | A list of search-key values used to find entities. At runtime, entities are found and then `:id-key` is used to set the proper id values that Auth0 expects |
| :dependency-entity-type | Keyword | The Auth0 entity type for each element in `dependency-value` |
| :dependency-key | Keyword | The field id for the entity whose value is a list of ids to another entity type |
| :id-key | Keyword | Maps to the Auth0 generated id of an entity. This key is used to access each dependency in order to properly set values |
| :search-key | Keyword | Represents the key to use to uniquely identify an entity |
| :search-value | String| Represents the value to use to uniquely identify an entity |

```
NOTE: `:referential-dependency` nodes need to occur sequentially AFTER the entities they refer to. This was
intentionally done to make it easier to talk about the relationships you want to establish, without having to
create logic to analyze these desires implicitly.
```

## api-actions

Each `edn-config-entry` is processed and turned into an `api-action`.

`:entity` entries will generate calls to the Auth0 API to determine if entities exists, or if it needs to create
them. This information is passed through to the `api-action`. The `api-actions` created from these entries contain all of the information needed to create or update an entity. In the case of create this will determine the whole
payload to use, while an update will only patch the diff necessary to establish the target configuration. In the
case where there are no differences, noop api-actions are created.

`:referential-dependency` entries don't make any calls, and are assumed to refer to entities that either will be
created, or already exist. The static data is passed through to the `api-action` in order to do lookups and
perform API requests at runtime.

These `api-actions` are then sequentially consumed to transact information to Auth0.

## Usage

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
