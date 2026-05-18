{{- define "rdmmesh.name" -}}rdmmesh{{- end -}}

{{- define "rdmmesh.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "rdmmesh.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "rdmmesh.labels" -}}
app.kubernetes.io/name: {{ include "rdmmesh.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "rdmmesh.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rdmmesh.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "rdmmesh.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/* Внешний Secret (Vault/SOPS); чарт его НЕ создаёт (SPEC §3.7). */}}
{{- define "rdmmesh.secretName" -}}
{{- required "secret.existingSecret обязателен (Secret создаётся вне чарта — Vault/SOPS)" .Values.secret.existingSecret -}}
{{- end -}}
