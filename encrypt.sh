./mvnw jasypt:encrypt-value \
  -Djasypt.encryptor.password=$JASYPT_ENCRYPTOR_PASSWORD \
  -Djasypt.plugin.value=$1

