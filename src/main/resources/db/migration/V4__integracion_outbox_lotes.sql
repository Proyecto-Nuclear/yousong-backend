-- Dictionary: "Diccionario de eventos y procesamiento".

CREATE TABLE integracion.evento_outbox (
    event_id           uuid        NOT NULL,
    event_type         varchar(64) NOT NULL,
    schema_version     integer     NOT NULL DEFAULT 1,
    occurred_at        timestamptz NOT NULL,
    establecimiento_id uuid,
    sesion_id          uuid,
    agregado_tipo      varchar(40) NOT NULL,
    agregado_id        uuid        NOT NULL,
    agregado_version   bigint      NOT NULL DEFAULT 0,
    correlacion_id     uuid        NOT NULL,
    causacion_id       uuid,
    actor_ref          varchar(64),
    productor          varchar(60) NOT NULL,
    ambito             varchar(1)  NOT NULL,
    payload            jsonb       NOT NULL,
    publicado_at       timestamptz,
    intentos_entrega   integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_evento_outbox PRIMARY KEY (event_id),
    CONSTRAINT fk_evento_outbox_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT fk_evento_outbox_sesion FOREIGN KEY (sesion_id) REFERENCES operacion.sesion (id),
    CONSTRAINT uq_evento_outbox_agregado UNIQUE (agregado_tipo, agregado_id, agregado_version, event_type),
    CONSTRAINT ck_evento_outbox_schema CHECK (schema_version > 0),
    CONSTRAINT ck_evento_outbox_agregado_version CHECK (agregado_version >= 0),
    -- B business, O operation/quality, R restricted security/privacy.
    CONSTRAINT ck_evento_outbox_ambito CHECK (ambito IN ('B', 'O', 'R')),
    CONSTRAINT ck_evento_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_evento_outbox_intentos CHECK (intentos_entrega >= 0)
);
CREATE INDEX ix_evento_outbox_entrega ON integracion.evento_outbox (publicado_at, occurred_at);

CREATE TABLE integracion.lote_analitico (
    id               uuid         NOT NULL,
    rango_desde      timestamptz  NOT NULL,
    rango_hasta      timestamptz  NOT NULL,
    iniciado_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalizado_at    timestamptz,
    estado           varchar(16)  NOT NULL DEFAULT 'EN_CURSO',
    leidos           bigint       NOT NULL DEFAULT 0,
    aceptados        bigint       NOT NULL DEFAULT 0,
    duplicados       bigint       NOT NULL DEFAULT 0,
    rechazados       bigint       NOT NULL DEFAULT 0,
    pendientes       bigint       NOT NULL DEFAULT 0,
    ruta_parquet     varchar(300),
    contrato_version integer      NOT NULL DEFAULT 1,
    CONSTRAINT pk_lote_analitico PRIMARY KEY (id),
    CONSTRAINT ck_lote_estado CHECK (estado IN ('EN_CURSO', 'VALIDADO', 'PUBLICADO', 'FALLIDO')),
    CONSTRAINT ck_lote_contadores_no_negativos CHECK (
        leidos >= 0 AND aceptados >= 0 AND duplicados >= 0 AND rechazados >= 0 AND pendientes >= 0),
    CONSTRAINT ck_lote_conciliacion CHECK (leidos = aceptados + duplicados + rechazados + pendientes),
    -- Final states require every record classified.
    CONSTRAINT ck_lote_estado_final_sin_pendientes CHECK (estado = 'EN_CURSO' OR pendientes = 0),
    CONSTRAINT ck_lote_contrato CHECK (contrato_version > 0)
);
CREATE INDEX ix_lote_iniciado ON integracion.lote_analitico (iniciado_at);
CREATE INDEX ix_lote_estado ON integracion.lote_analitico (estado);

CREATE TABLE integracion.rechazo_ingesta (
    id            uuid         NOT NULL,
    lote_id       uuid         NOT NULL,
    event_id      uuid,
    regla_codigo  varchar(60)  NOT NULL,
    campo_ruta    varchar(120),
    detectado_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registro_ref  varchar(300),
    CONSTRAINT pk_rechazo_ingesta PRIMARY KEY (id),
    CONSTRAINT fk_rechazo_ingesta_lote FOREIGN KEY (lote_id) REFERENCES integracion.lote_analitico (id),
    CONSTRAINT uq_rechazo_ingesta UNIQUE (lote_id, event_id, regla_codigo)
);
