#!/usr/bin/env python3
"""Generates the base Grafana dashboards of the platform (dashboards/*.json).

    python3 scripts/dashboards.py

Every query uses the labels the framework adds to every meter (service, env, version, domain) and
the meters it emits: the generic event instruments (axiz_events_*), the observations at
architecture points (axiz_usecase, axiz_event_handler, axiz_outbox_relay, axiz_client,
axiz_aws_sdk...), the outbox and saga meters and the Spring Boot / Micrometer standard ones.
The dashboards are provisioned by the platform team (Grafana folder "axiz") and imported locally
by the otel-lgtm container of each service.
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), '..', 'dashboards')
DS = {'type': 'prometheus', 'uid': '${datasource}'}


def var_datasource():
    return {'name': 'datasource', 'label': 'Prometheus', 'type': 'datasource', 'query': 'prometheus',
            'current': {}, 'hide': 0}


def var_query(name, label, query, multi=True, include_all=True):
    return {'name': name, 'label': label, 'type': 'query', 'datasource': DS,
            'query': {'query': query, 'refId': name}, 'definition': query, 'refresh': 2,
            'multi': multi, 'includeAll': include_all, 'allValue': '.*', 'sort': 1,
            'current': {'selected': True, 'text': ['All'], 'value': ['$__all']} if include_all else {}}


class Board:
    def __init__(self, uid, title, description, variables):
        self.uid, self.title, self.description = uid, title, description
        self.variables = [var_datasource()] + variables
        self.panels = []
        self.y = 0
        self.x = 0

    def row(self, title):
        self._newline()
        self.panels.append({'type': 'row', 'title': title, 'collapsed': False,
                            'gridPos': {'h': 1, 'w': 24, 'x': 0, 'y': self.y}, 'panels': []})
        self.y += 1

    def _newline(self):
        if self.x:
            self.x = 0
            self.y += 8

    def panel(self, title, exprs, unit='short', kind='timeseries', width=12, description='',
              legend=None):
        if self.x + width > 24:
            self._newline()
        targets = []
        for i, e in enumerate(exprs if isinstance(exprs, list) else [exprs]):
            expr, fmt = e if isinstance(e, tuple) else (e, legend)
            t = {'datasource': DS, 'expr': expr, 'refId': chr(65 + i)}
            if fmt:
                t['legendFormat'] = fmt
            targets.append(t)
        p = {'type': kind, 'title': title, 'datasource': DS, 'targets': targets,
             'gridPos': {'h': 8, 'w': width, 'x': self.x, 'y': self.y},
             'fieldConfig': {'defaults': {'unit': unit}, 'overrides': []}, 'options': {}}
        if description:
            p['description'] = description
        if kind == 'timeseries':
            p['options'] = {'legend': {'displayMode': 'table', 'placement': 'bottom',
                                       'calcs': ['mean', 'max']},
                            'tooltip': {'mode': 'multi'}}
        if kind == 'stat':
            p['options'] = {'reduceOptions': {'calcs': ['lastNotNull']}, 'colorMode': 'value'}
        self.panels.append(p)
        self.x += width
        if self.x >= 24:
            self._newline()

    def json(self):
        for i, p in enumerate(self.panels, 1):
            p['id'] = i
        return {'uid': self.uid, 'title': self.title, 'description': self.description,
                'tags': ['axiz'], 'timezone': 'browser', 'schemaVersion': 39, 'version': 1,
                'editable': True, 'graphTooltip': 1, 'refresh': '30s',
                'time': {'from': 'now-6h', 'to': 'now'},
                'templating': {'list': self.variables}, 'panels': self.panels,
                'annotations': {'list': []}, 'links': [
                    {'title': 'axiz', 'type': 'dashboards', 'tags': ['axiz'], 'asDropdown': True}]}


SEL = 'env=~"$env", service=~"$service"'
COMMON_VARS = [
    var_query('env', 'Ambiente', 'label_values(axiz_events_total, env)', multi=False, include_all=True),
    var_query('service', 'Servicio', 'label_values(process_uptime_seconds{env=~"$env"}, service)'),
]


def events():
    b = Board('axiz-events', 'axiz · eventos',
              'Métricas genéricas por evento: cualquier evento de negocio con $event, $channel y $product.',
              COMMON_VARS + [
                  var_query('event', 'Evento', f'label_values(axiz_events_total{{{SEL}}}, event)'),
                  var_query('channel', 'Canal', f'label_values(axiz_events_total{{{SEL}}}, channel)'),
                  var_query('product', 'Producto', f'label_values(axiz_events_total{{{SEL}}}, product)'),
              ])
    s = f'{SEL}, event=~"$event", channel=~"$channel|", product=~"$product|"'
    b.row('Volumen y resultado')
    b.panel('Eventos por minuto', f'sum by (event) (rate(axiz_events_total{{{s}}}[$__rate_interval])) * 60',
            legend='{{event}}')
    b.panel('Ratio de éxito', f'sum by (event) (rate(axiz_events_total{{{s}, outcome="success"}}[$__rate_interval])) '
            f'/ sum by (event) (rate(axiz_events_total{{{s}, outcome!=""}}[$__rate_interval]))',
            unit='percentunit', legend='{{event}}')
    b.panel('Resultado por motivo (última hora)', f'sum by (event, outcome, reason) (increase(axiz_events_total{{{s}, outcome!="success"}}[1h]))',
            kind='table', width=12)
    b.panel('Eventos por canal', f'sum by (channel) (rate(axiz_events_total{{{s}}}[$__rate_interval])) * 60',
            legend='{{channel}}')
    b.row('Montos')
    b.panel('Monto por minuto por moneda', f'sum by (currency) (rate(axiz_events_amount_sum{{{s}}}[$__rate_interval])) * 60',
            legend='{{currency}}')
    b.panel('Ticket promedio', f'sum by (currency) (rate(axiz_events_amount_sum{{{s}}}[$__rate_interval])) '
            f'/ sum by (currency) (rate(axiz_events_amount_count{{{s}}}[$__rate_interval]))', legend='{{currency}}')
    b.panel('Ticket p95', f'histogram_quantile(0.95, sum by (currency, le) (rate(axiz_events_amount_bucket{{{s}}}[$__rate_interval])))',
            legend='{{currency}}')
    b.panel('Monto acumulado (rango)', f'sum by (currency) (increase(axiz_events_amount_sum{{{s}}}[$__range]))',
            kind='stat', width=12)
    b.row('Duración y procesos')
    b.panel('Duración p50 / p95 / p99', [
        (f'histogram_quantile({q}, sum by (le) (rate(axiz_events_duration_seconds_bucket{{{s}}}[$__rate_interval])))', f'p{int(q * 100)}')
        for q in (0.5, 0.95, 0.99)], unit='s')
    b.panel('Valores (registros, ítems...)', f'sum by (event, stage) (rate(axiz_events_value_sum{{{s}}}[$__rate_interval])) * 60',
            legend='{{event}} {{stage}}')
    b.panel('En curso (nivel actual)', f'sum by (event) (axiz_events_active{{{s}}})', legend='{{event}}')
    b.panel('Procesos largos activos', f'sum by (event) (axiz_events_inflight_seconds_active_count{{{s}}})', legend='{{event}}')
    b.panel('Embudo por etapa (última hora)', f'sum by (stage) (increase(axiz_events_total{{{s}, stage!=""}}[1h]))',
            kind='barchart', width=12)
    b.panel('Valores observados', f'max by (event) (axiz_events_gauge{{{s}}})', legend='{{event}}')
    return b


def red():
    b = Board('axiz-red', 'axiz · servicios (RED)',
              'Tasa, errores y duración de cada servicio: HTTP de entrada, casos de uso, commands y queries.',
              COMMON_VARS + [var_query('uri', 'Ruta', f'label_values(http_server_requests_seconds_count{{{SEL}}}, uri)')])
    h = f'{SEL}, uri=~"$uri"'
    b.row('HTTP de entrada')
    b.panel('Requests por segundo', f'sum by (service) (rate(http_server_requests_seconds_count{{{h}}}[$__rate_interval]))',
            unit='reqps', legend='{{service}}')
    b.panel('Ratio de errores 5xx', f'sum by (service) (rate(http_server_requests_seconds_count{{{h}, status=~"5.."}}[$__rate_interval])) '
            f'/ sum by (service) (rate(http_server_requests_seconds_count{{{h}}}[$__rate_interval]))',
            unit='percentunit', legend='{{service}}')
    b.panel('Latencia p95', f'histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket{{{h}}}[$__rate_interval])))',
            unit='s', legend='{{service}}')
    b.panel('Respuestas 4xx por ruta (última hora)', f'sum by (service, uri, status) (increase(http_server_requests_seconds_count{{{h}, status=~"4.."}}[1h]))',
            kind='table')
    b.row('Casos de uso, commands y queries')
    for name, metric in (('Casos de uso', 'axiz_usecase'), ('Commands', 'axiz_command'), ('Queries', 'axiz_query')):
        b.panel(f'{name}: ejecuciones por resultado', f'sum by (axiz_operation, axiz_outcome) (rate({metric}_seconds_count{{{SEL}}}[$__rate_interval]))',
                unit='ops', legend='{{axiz_operation}} {{axiz_outcome}}', width=8)
    for name, metric in (('Casos de uso', 'axiz_usecase'), ('Commands', 'axiz_command'), ('Queries', 'axiz_query')):
        b.panel(f'{name}: duración media', f'sum by (axiz_operation) (rate({metric}_seconds_sum{{{SEL}}}[$__rate_interval])) '
                f'/ sum by (axiz_operation) (rate({metric}_seconds_count{{{SEL}}}[$__rate_interval]))',
                unit='s', legend='{{axiz_operation}}', width=8)
    b.row('Saturación')
    b.panel('CPU del proceso', f'max by (service) (process_cpu_usage{{{SEL}}})', unit='percentunit', legend='{{service}}', width=8)
    b.panel('Heap usado', f'sum by (service) (jvm_memory_used_bytes{{{SEL}, area="heap"}})', unit='bytes', legend='{{service}}', width=8)
    b.panel('Virtual threads / threads vivos', f'max by (service) (jvm_threads_live_threads{{{SEL}}})', legend='{{service}}', width=8)
    b.panel('Conexiones del pool JDBC (activas / máximo)', [
        (f'sum by (service) (hikaricp_connections_active{{{SEL}}})', '{{service}} activas'),
        (f'sum by (service) (hikaricp_connections_max{{{SEL}}})', '{{service}} máximo')], width=12)
    b.panel('Rate limit y circuit breakers', [
        (f'sum by (service) (rate(http_server_requests_seconds_count{{{h}, status="429"}}[$__rate_interval]))', '{{service}} 429'),
        (f'sum by (service, name) (resilience4j_circuitbreaker_state{{{SEL}, state="open"}})', '{{service}} {{name}} abierto')], width=12)
    return b


def messaging():
    b = Board('axiz-messaging', 'axiz · mensajería',
              'Publicación y consumo de eventos: handlers, inbox, outbox, lag de Kafka y sagas.',
              COMMON_VARS + [var_query('type', 'Tipo de evento',
                                       f'label_values(axiz_event_handler_seconds_count{{{SEL}}}, axiz_event_type)')])
    t = f'{SEL}, axiz_event_type=~"$type"'
    b.row('Consumo')
    b.panel('Eventos procesados (error = none: éxito)', f'sum by (axiz_event_type, error) (rate(axiz_event_handler_seconds_count{{{t}}}[$__rate_interval]))',
            unit='ops', legend='{{axiz_event_type}} {{error}}')
    b.panel('Duración media del handler', f'sum by (axiz_event_type) (rate(axiz_event_handler_seconds_sum{{{t}}}[$__rate_interval])) '
            f'/ sum by (axiz_event_type) (rate(axiz_event_handler_seconds_count{{{t}}}[$__rate_interval]))',
            unit='s', legend='{{axiz_event_type}}')
    b.panel('Fallos de handlers (reintento o DLQ)', f'sum by (axiz_event_type, error) (increase(axiz_event_handler_seconds_count{{{t}, error!="none"}}[1h]))',
            kind='table',
            description='Los fallos transitorios se reintentan con backoff; los permanentes van a la DLQ. '
                        'La profundidad de las DLQ de SQS está en CloudWatch (ApproximateNumberOfMessagesVisible).')
    b.panel('Lag de consumidores Kafka (máximo por tópico)', f'max by (service, topic) (kafka_consumer_fetch_manager_records_lag_max{{{SEL}}})',
            legend='{{service}} {{topic}}')
    b.row('Publicación y outbox')
    b.panel('Eventos publicados por destino', f'sum by (axiz_destination, error) (rate(axiz_event_publish_seconds_count{{{SEL}}}[$__rate_interval]))',
            unit='ops', legend='{{axiz_destination}} {{error}}')
    b.panel('Outbox pendiente', f'max by (service) (axiz_outbox_pending{{{SEL}}})', legend='{{service}}',
            description='Registros esperando al relay. Debe volver a cero; si crece, el broker o el relay están fallando.')
    b.panel('Relay del outbox: lotes (error = none: éxito)', f'sum by (service, error) (rate(axiz_outbox_relay_seconds_count{{{SEL}}}[$__rate_interval]))',
            unit='ops', legend='{{service}} {{error}}')
    b.panel('Latencia de publicación p95', f'histogram_quantile(0.95, sum by (axiz_destination, le) (rate(axiz_event_publish_seconds_bucket{{{SEL}}}[$__rate_interval])))',
            unit='s', legend='{{axiz_destination}}')
    b.row('Sagas')
    b.panel('Sagas terminadas por estado', f'sum by (saga, status) (increase(axiz_saga_finished_total{{{SEL}}}[1h]))',
            kind='barchart')
    b.panel('Compensaciones por paso', f'sum by (saga, step) (increase(axiz_saga_compensations_total{{{SEL}}}[1h]))',
            kind='barchart')
    return b


def dependencies():
    b = Board('axiz-dependencies', 'axiz · dependencias',
              'Llamadas salientes: clientes HTTP y SOAP por nombre lógico, servicios de AWS y bases de datos.',
              COMMON_VARS + [var_query('client', 'Cliente', f'label_values(http_client_requests_seconds_count{{{SEL}}}, client_name)')])
    c = f'{SEL}, client_name=~"$client"'
    b.row('HTTP')
    b.panel('Llamadas por cliente y status', f'sum by (client_name, status) (rate(http_client_requests_seconds_count{{{c}}}[$__rate_interval]))',
            unit='reqps', legend='{{client_name}} {{status}}')
    b.panel('Latencia p95 por cliente', f'histogram_quantile(0.95, sum by (client_name, le) (rate(http_client_requests_seconds_bucket{{{c}}}[$__rate_interval])))',
            unit='s', legend='{{client_name}}')
    b.panel('Errores por cliente (5xx, timeouts, conexión)', f'sum by (client_name, status, outcome) (increase(http_client_requests_seconds_count{{{c}, outcome!="SUCCESS"}}[1h]))',
            kind='table')
    b.panel('Circuit breakers', f'max by (name, state) (resilience4j_circuitbreaker_state{{{SEL}}})', legend='{{name}} {{state}}')
    b.row('SOAP y AWS')
    b.panel('SOAP por cliente (error = none: éxito)', f'sum by (axiz_client, error) (rate(axiz_soap_client_seconds_count{{{SEL}}}[$__rate_interval]))',
            unit='reqps', legend='{{axiz_client}} {{error}}')
    b.panel('AWS: llamadas por servicio y operación', f'sum by (aws_service, aws_operation) (rate(axiz_aws_sdk_seconds_count{{{SEL}}}[$__rate_interval]))',
            unit='reqps', legend='{{aws_service}} {{aws_operation}}')
    b.panel('AWS: duración media', f'sum by (aws_service) (rate(axiz_aws_sdk_seconds_sum{{{SEL}}}[$__rate_interval])) '
            f'/ sum by (aws_service) (rate(axiz_aws_sdk_seconds_count{{{SEL}}}[$__rate_interval]))', unit='s', legend='{{aws_service}}')
    b.panel('AWS: errores (última hora)', f'sum by (aws_service, aws_operation) (increase(axiz_aws_sdk_seconds_count{{{SEL}, outcome="error"}}[1h]))',
            kind='table')
    b.row('Datos')
    b.panel('Espera por conexión JDBC p95', f'histogram_quantile(0.95, sum by (service, le) (rate(hikaricp_connections_acquire_seconds_bucket{{{SEL}}}[$__rate_interval])))',
            unit='s', legend='{{service}}')
    b.panel('Comandos de Redis (Lettuce) p95', f'histogram_quantile(0.95, sum by (command, le) (rate(lettuce_command_completion_seconds_bucket{{{SEL}}}[$__rate_interval])))',
            unit='s', legend='{{command}}')
    return b


def main():
    os.makedirs(OUT, exist_ok=True)
    for build in (events, red, messaging, dependencies):
        board = build()
        path = os.path.join(OUT, board.uid + '.json')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(board.json(), f, indent=2, ensure_ascii=False)
            f.write('\n')
        print('wrote', os.path.normpath(path), len(board.panels), 'panels')


if __name__ == '__main__':
    main()
