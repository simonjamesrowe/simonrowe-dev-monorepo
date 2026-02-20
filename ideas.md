I would like this project to be my local website, and for this to contain a local mono repo.

Previously this project used a cms, but this time around I dont want to use a cms.

Backups of the data can be found here - contains mongodb and also content - /Users/simonrowe/backups

I still want to use mongodb as the persistance store for this. 

---
Would also like to use spring boot, kafka, java 25, , spring boot 4 elastic search as well for the backend tech stack.
Use gradle for build and dependency management. Want to build a docker container in github container registry, and will
be running this site using docker compose, and pinggy in a production setting. https://start.spring.io/

Would like actuator/management on different ports from the actual ports as want to expose prometheus metrics for scraping.
Also would like to have open telemetry.

Would be good to include coding standards from here: https://google.github.io/styleguide/javaguide.html

Jacoco for test coverage, sonar for static analysis - CycloneDX (CDX) BOM for Java.

Test containers for slice, integration tests.


---

Ideally will use react for the front end development. The existing website can be located here: /Users/simonrowe/workspace/simonjamesrowe/react-ui
Want a different container for the front end and the backend. 

Might need to use the latest version of react.

Would base the designs off the refereced version of the UI, but would be good to make these a bit more sleek.

---

Build should use github actions for the CI part, at least to build, run tests, build and publish docker images.

---

I will eventually want to build some functionality that allows for content creation and editing, so think about this.

Will use auth0 as auth for this, wont allow registrations, sign ups via the app. Instead will provision users (just me) directly in auth0.

---

All features from these 2 repos - should be multiple specs:
/Users/simonrowe/workspace/simonjamesrowe/react-ui
/Users/simonrowe/workspace/simonjamesrowe/backend-modulith



