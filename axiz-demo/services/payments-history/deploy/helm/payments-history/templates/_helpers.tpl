{{/* Chart name */}}
{{- define "service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Resource name: the service name, so it matches spring.application.name and its ConfigMaps */}}
{{- define "service.fullname" -}}
{{- include "service.name" . -}}
{{- end -}}

{{- define "service.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{ include "service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: axiz
axiz.io/environment: {{ .Values.environment | quote }}
{{- end -}}

{{- define "service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Pod spec shared by the Deployment and the CronJob: include with (dict "root" . "server" true|false) */}}
{{- define "service.podSpec" -}}
{{- $root := .root -}}
{{- $values := .root.Values -}}
serviceAccountName: {{ include "service.serviceAccountName" $root }}
automountServiceAccountToken: true
{{- with $values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
securityContext:
  {{- toYaml $values.podSecurityContext | nindent 2 }}
containers:
  - name: {{ include "service.name" $root }}
    image: "{{ $values.image.repository }}:{{ $values.image.tag | default $root.Chart.AppVersion }}{{ with $values.image.digest }}@{{ . }}{{ end }}"
    imagePullPolicy: {{ $values.image.pullPolicy }}
    securityContext:
      {{- toYaml $values.securityContext | nindent 6 }}
    env:
      - name: JAVA_TOOL_OPTIONS
        value: {{ $values.javaOpts | quote }}
      - name: AXIZ_ENVIRONMENT
        value: {{ $values.environment | quote }}
      - name: OTEL_SERVICE_NAME
        value: {{ include "service.name" $root }}
      {{- with $values.env }}
      {{- toYaml . | nindent 6 }}
      {{- end }}
    {{- if .server }}
    ports:
      - name: http
        containerPort: 8080
        protocol: TCP
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: 5
      failureThreshold: {{ $values.probes.startupFailureThreshold }}
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: 10
      failureThreshold: 3
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: http
      periodSeconds: 5
      failureThreshold: 3
    lifecycle:
      preStop:
        sleep:
          seconds: 5
    {{- end }}
    resources:
      {{- toYaml $values.resources | nindent 6 }}
    volumeMounts:
      - name: tmp
        mountPath: /tmp
      {{- if $values.externalSecret.keys }}
      - name: secrets
        mountPath: /var/run/secrets/axiz
        readOnly: true
      {{- end }}
volumes:
  - name: tmp
    emptyDir: {}
  {{- if $values.externalSecret.keys }}
  - name: secrets
    secret:
      secretName: {{ include "service.fullname" $root }}-secrets
      defaultMode: 0400
  {{- end }}
{{- end -}}
