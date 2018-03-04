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

An `edn` config file contains a vector of hash-maps that represent the state of the Auth0 enviornment you are
trying to setup.

Each hash-maps has `:type`, `:id`, `:key`, and `:payload` keys.
* The `:type` key is a keyword, one of `#{:client :resource-server :connection :user}`.
```
NOTE: For most of the Auth0 API, there is a consistent mapping from entity type to url.
While not all types have yet been explicitly supported, it should be faily straight-forward
to add more types, or rely on a rule to open them up. This repo is still beta, so take it with
a grain of salt.
```
* The `:id` key is a keyword that maps to the Auth0 generated id of an entity. This key is used to collect
information to report to the user after the program runs to allow the user to verify updated entities.
* The `:key` key is a keyword that represents the edn based identifier used to detect existing entities. It is used
by the program to determine if an entity already exists.
* The `:payload` key is an edn data structure that will be transformed to a json payload to use as the body for
either a POST/PUT to create/update an entity.
```
NOTE: kebab-case keywords will be converted to snake_case strings
```

This program will first consume the `edn`, get an Auth0 access-token, and sequentially process the edn using
calls to the Auth0 API to determine if entities exists, or if it needs to create them. The program will create
another data-structure based on this information to communicate the steps necessary to get from where the current
state to the desired state. This data structure is then used to actually perform the changes. The program captures
the ids of the entities that it created in order to allow the user to verify the work in the dashboard.

TODO: Document environment variables

Run the project directly:

    $ boot run

Run the project's tests (they'll fail until you edit them):

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
