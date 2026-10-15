{{- define "memo.labels" -}}
app.kubernetes.io/part-of: memo
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "memo.selector" -}}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/name: {{ .name }}
{{- end -}}

{{/* Restricted pod security (AGENTS.md §3.4, ADR-0012): non-root, read-only root FS, no capabilities. */}}
{{- define "memo.podSecurity" -}}
runAsNonRoot: true
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "memo.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{/* Environment shared by the backend and the migration job (no secrets here). */}}
{{- define "memo.backendEnv" -}}
- name: MEMO_DB_URL
  value: {{ .Values.database.url | quote }}
- name: LOGGING_STRUCTURED_FORMAT_CONSOLE
  value: {{ .Values.observability.logFormat | quote }}
- name: MEMO_OTEL_EXPORT
  value: {{ .Values.observability.otelExport | quote }}
- name: SPRING_PROFILES_ACTIVE
  value: prod
{{- end -}}
