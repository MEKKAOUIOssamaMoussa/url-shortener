create table short_url (
    id bigint generated always as identity primary key,
    code varchar(16) not null,
    target_url text not null,
    created_at timestamptz not null default now(),
    constraint uq_short_url_code unique (code)
);

