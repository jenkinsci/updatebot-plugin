## 1.0.9

* lets put all imported projects under the `GitHub` root folder to avoid clashing with fabric8/openshift.io github org folder jobs
 
## 1.0.8

* fixes possible ClassCastException with import github repository build
 
## 1.0.6

* import git repository waits for any pending PR to be merged
* import git repository accepts full https://github.com/org/repo URLs
* allow the Jenkinsfile library git repository to be configurable

## 1.0.3

* adds an import github repository and enable CI / CD build for importing projects and enabling CI / CD through fabric8

## 1.0.2

* adds support for ansicolor for pretty logs
