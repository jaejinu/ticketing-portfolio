{{/*
공통 이름/라벨 헬퍼 — k8s 권장 라벨 세트를 한곳에서.
*/}}
{{- define "ticketing-backend.name" -}}
{{ .Chart.Name }}
{{- end }}

{{- define "ticketing-backend.labels" -}}
app.kubernetes.io/name: {{ include "ticketing-backend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: ticketing-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "ticketing-backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ticketing-backend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
