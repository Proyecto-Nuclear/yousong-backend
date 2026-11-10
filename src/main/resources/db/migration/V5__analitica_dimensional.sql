-- Dictionary: "Diccionario del modelo analítico".
-- Analytic FKs point to analytic dimensions and the batch, never to operational tables,
-- so retention and processing do not depend on active credentials or technical contexts.

CREATE TABLE analitica.dim_establecimiento (
    id           uuid         NOT NULL,
    nombre       varchar(120) NOT NULL,
    zona_horaria varchar(64)  NOT NULL,
    CONSTRAINT pk_dim_establecimiento PRIMARY KEY (id)
);

CREATE TABLE analitica.dim_sesion (
    id                 uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    fecha_operativa    date        NOT NULL,
    estado             varchar(16) NOT NULL,
    abierta_at         timestamptz NOT NULL,
    cerrada_at         timestamptz,
    CONSTRAINT pk_dim_sesion PRIMARY KEY (id),
    CONSTRAINT fk_dim_sesion_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES analitica.dim_establecimiento (id),
    CONSTRAINT ck_dim_sesion_estado CHECK (estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO', 'CERRADA'))
);
CREATE INDEX ix_dim_sesion_fecha ON analitica.dim_sesion (establecimiento_id, fecha_operativa);

CREATE TABLE analitica.dim_tiempo (
    id          integer  NOT NULL, -- YYYYMMDDHH
    fecha_local date     NOT NULL,
    hora        smallint NOT NULL,
    dia_semana  smallint NOT NULL,
    CONSTRAINT pk_dim_tiempo PRIMARY KEY (id),
    CONSTRAINT uq_dim_tiempo_fecha_hora UNIQUE (fecha_local, hora),
    CONSTRAINT ck_dim_tiempo_hora CHECK (hora BETWEEN 0 AND 23),
    CONSTRAINT ck_dim_tiempo_dia CHECK (dia_semana BETWEEN 1 AND 7)
);

CREATE TABLE analitica.dim_cancion (
    id                   uuid         NOT NULL,
    establecimiento_id   uuid         NOT NULL,
    cancion_ref          uuid         NOT NULL,
    genero_propio        varchar(60),
    fuente_clasificacion varchar(200),
    CONSTRAINT pk_dim_cancion PRIMARY KEY (id),
    CONSTRAINT fk_dim_cancion_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES analitica.dim_establecimiento (id),
    CONSTRAINT uq_dim_cancion_ref UNIQUE (establecimiento_id, cancion_ref)
);

CREATE TABLE analitica.dim_lista (
    id                 uuid         NOT NULL,
    establecimiento_id uuid         NOT NULL,
    nombre             varchar(100) NOT NULL,
    version            integer      NOT NULL,
    CONSTRAINT pk_dim_lista PRIMARY KEY (id),
    CONSTRAINT fk_dim_lista_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES analitica.dim_establecimiento (id),
    CONSTRAINT ck_dim_lista_version CHECK (version > 0)
);
CREATE INDEX ix_dim_lista_establecimiento ON analitica.dim_lista (establecimiento_id);

CREATE TABLE analitica.dim_participacion (
    id              uuid        NOT NULL,
    sesion_id       uuid        NOT NULL,
    participante_ref varchar(64) NOT NULL,
    canal           varchar(12) NOT NULL,
    ingreso_at      timestamptz NOT NULL,
    CONSTRAINT pk_dim_participacion PRIMARY KEY (id),
    CONSTRAINT fk_dim_participacion_sesion FOREIGN KEY (sesion_id) REFERENCES analitica.dim_sesion (id),
    -- HMAC-SHA-256 per session; the key never ships with the dataset.
    CONSTRAINT uq_dim_participacion_ref UNIQUE (sesion_id, participante_ref),
    CONSTRAINT ck_dim_participacion_ref CHECK (participante_ref ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dim_participacion_canal CHECK (canal IN ('WEB', 'ANDROID'))
);

CREATE TABLE analitica.hecho_solicitud (
    solicitud_ref    uuid          NOT NULL,
    sesion_id        uuid          NOT NULL,
    cancion_id       uuid          NOT NULL,
    participacion_id uuid          NOT NULL,
    tiempo_id        integer       NOT NULL,
    creada_at        timestamptz   NOT NULL,
    primer_inicio_at timestamptz,
    estado           varchar(24)   NOT NULL,
    espera_real_s    numeric(12,3), -- NULL when the request never started (not zero)
    lote_id          uuid          NOT NULL,
    CONSTRAINT pk_hecho_solicitud PRIMARY KEY (solicitud_ref),
    CONSTRAINT fk_hecho_solicitud_sesion FOREIGN KEY (sesion_id) REFERENCES analitica.dim_sesion (id),
    CONSTRAINT fk_hecho_solicitud_cancion FOREIGN KEY (cancion_id) REFERENCES analitica.dim_cancion (id),
    CONSTRAINT fk_hecho_solicitud_participacion FOREIGN KEY (participacion_id)
        REFERENCES analitica.dim_participacion (id),
    CONSTRAINT fk_hecho_solicitud_tiempo FOREIGN KEY (tiempo_id) REFERENCES analitica.dim_tiempo (id),
    CONSTRAINT fk_hecho_solicitud_lote FOREIGN KEY (lote_id) REFERENCES integracion.lote_analitico (id),
    CONSTRAINT ck_hecho_solicitud_estado CHECK (estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO', 'ATENDIDA',
        'SALTADA', 'FALLIDA', 'BLOQUEADA', 'NO_ATENDIDA_CIERRE')),
    CONSTRAINT ck_hecho_solicitud_espera CHECK (espera_real_s IS NULL OR espera_real_s >= 0)
);
CREATE INDEX ix_hecho_solicitud_estado ON analitica.hecho_solicitud (sesion_id, estado);

CREATE TABLE analitica.hecho_voto (
    voto_ref         uuid        NOT NULL,
    solicitud_ref    uuid        NOT NULL,
    sesion_id        uuid        NOT NULL,
    cancion_id       uuid        NOT NULL,
    participacion_id uuid        NOT NULL,
    tiempo_id        integer     NOT NULL,
    creado_at        timestamptz NOT NULL,
    es_inicial       boolean     NOT NULL,
    lote_id          uuid        NOT NULL,
    CONSTRAINT pk_hecho_voto PRIMARY KEY (voto_ref),
    CONSTRAINT fk_hecho_voto_solicitud FOREIGN KEY (solicitud_ref)
        REFERENCES analitica.hecho_solicitud (solicitud_ref),
    CONSTRAINT fk_hecho_voto_sesion FOREIGN KEY (sesion_id) REFERENCES analitica.dim_sesion (id),
    CONSTRAINT fk_hecho_voto_cancion FOREIGN KEY (cancion_id) REFERENCES analitica.dim_cancion (id),
    CONSTRAINT fk_hecho_voto_participacion FOREIGN KEY (participacion_id)
        REFERENCES analitica.dim_participacion (id),
    CONSTRAINT fk_hecho_voto_tiempo FOREIGN KEY (tiempo_id) REFERENCES analitica.dim_tiempo (id),
    CONSTRAINT fk_hecho_voto_lote FOREIGN KEY (lote_id) REFERENCES integracion.lote_analitico (id),
    CONSTRAINT uq_hecho_voto_participacion_cancion UNIQUE (participacion_id, cancion_id)
);
CREATE INDEX ix_hecho_voto_sesion ON analitica.hecho_voto (sesion_id);

CREATE TABLE analitica.hecho_reproduccion (
    reproduccion_ref uuid        NOT NULL,
    sesion_id        uuid        NOT NULL,
    cancion_id       uuid        NOT NULL,
    origen           varchar(12) NOT NULL,
    solicitud_ref    uuid,
    lista_id         uuid,
    preparada_at     timestamptz NOT NULL,
    iniciada_at      timestamptz,
    terminada_at     timestamptz,
    resultado        varchar(24),
    lote_id          uuid        NOT NULL,
    CONSTRAINT pk_hecho_reproduccion PRIMARY KEY (reproduccion_ref),
    CONSTRAINT fk_hecho_reproduccion_sesion FOREIGN KEY (sesion_id) REFERENCES analitica.dim_sesion (id),
    CONSTRAINT fk_hecho_reproduccion_cancion FOREIGN KEY (cancion_id) REFERENCES analitica.dim_cancion (id),
    CONSTRAINT fk_hecho_reproduccion_solicitud FOREIGN KEY (solicitud_ref)
        REFERENCES analitica.hecho_solicitud (solicitud_ref),
    CONSTRAINT fk_hecho_reproduccion_lista FOREIGN KEY (lista_id) REFERENCES analitica.dim_lista (id),
    CONSTRAINT fk_hecho_reproduccion_lote FOREIGN KEY (lote_id) REFERENCES integracion.lote_analitico (id),
    CONSTRAINT ck_hecho_reproduccion_origen CHECK (
        (origen = 'SOLICITUD' AND solicitud_ref IS NOT NULL AND lista_id IS NULL)
        OR (origen = 'DEFECTO' AND lista_id IS NOT NULL AND solicitud_ref IS NULL)),
    CONSTRAINT ck_hecho_reproduccion_resultado CHECK (resultado IS NULL
        OR resultado IN ('COMPLETADA', 'SALTADA', 'FALLIDA', 'INTERRUMPIDA_CIERRE')),
    CONSTRAINT ck_hecho_reproduccion_completada CHECK (resultado <> 'COMPLETADA' OR iniciada_at IS NOT NULL)
);
CREATE INDEX ix_hecho_reproduccion_resultado ON analitica.hecho_reproduccion (sesion_id, resultado);

CREATE TABLE analitica.publicacion_sesion (
    sesion_id        uuid        NOT NULL,
    lote_id          uuid        NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    publicada_at     timestamptz NOT NULL,
    corte_eventos_at timestamptz NOT NULL,
    CONSTRAINT pk_publicacion_sesion PRIMARY KEY (sesion_id),
    CONSTRAINT fk_publicacion_sesion_sesion FOREIGN KEY (sesion_id) REFERENCES analitica.dim_sesion (id),
    CONSTRAINT fk_publicacion_sesion_lote FOREIGN KEY (lote_id) REFERENCES integracion.lote_analitico (id),
    CONSTRAINT ck_publicacion_sesion_version CHECK (version >= 0)
);
CREATE INDEX ix_publicacion_sesion_lote ON analitica.publicacion_sesion (lote_id);
