create table if not exists claw_instance (
    id uuid primary key,
    name varchar(128) not null,
    host_id uuid not null,
    image varchar(255) not null,
    gateway_host_port integer null,
    runtime varchar(32) not null,
    status varchar(32) not null,
    desired_state varchar(32) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

alter table claw_instance
    add column if not exists gateway_host_port integer null;

create index if not exists idx_claw_instance_created_at on claw_instance (created_at);
create index if not exists idx_claw_instance_host_id on claw_instance (host_id);
create unique index if not exists uq_claw_instance_host_port
    on claw_instance (host_id, gateway_host_port)
    where gateway_host_port is not null;

create table if not exists instance_action (
    id uuid primary key,
    instance_id uuid not null references claw_instance (id) on delete cascade,
    action varchar(32) not null,
    reason text null,
    accepted_at timestamptz not null
);

create index if not exists idx_instance_action_instance_id on instance_action (instance_id);
create index if not exists idx_instance_action_accepted_at on instance_action (accepted_at);
