{{/*
Names and labels shared by every template. One place, so a service is labelled the same way
by its Deployment, its Service and its ConfigMap, and a selector cannot drift from a label.
*/}}

{{- define "mizan.labels" -}}
app.kubernetes.io/part-of: mizan
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "mizan.selector" -}}
app.kubernetes.io/name: {{ . }}
app.kubernetes.io/part-of: mizan
{{- end }}

{{/* The Secret every service reads its credentials from: the one supplied, or the chart's. */}}
{{- define "mizan.credentialsSecret" -}}
{{- if .Values.credentials.existingSecret -}}
{{ .Values.credentials.existingSecret }}
{{- else -}}
{{ .Release.Name }}-credentials
{{- end -}}
{{- end }}

{{/*
The environment variable a credential key is delivered as. The services already read these
names (application.yml); the chart only decides where the values come from.
*/}}
{{- define "mizan.credentialEnv" -}}
{{- $names := dict
      "databasePassword" "MIZAN_DB_PASSWORD"
      "internalServiceToken" "MIZAN_INTERNAL_SERVICE_TOKEN"
      "apiKeyEncryptionKey" "MIZAN_API_KEY_ENCRYPTION_KEY"
      "webhookEncryptionKey" "MIZAN_WEBHOOK_ENCRYPTION_KEY"
      "jwtPrivateKey" "MIZAN_JWT_PRIVATE_KEY" -}}
{{- required (printf "unknown credential %q" .) (get $names .) -}}
{{- end }}
