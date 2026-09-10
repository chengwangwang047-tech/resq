# ResQ Domain Draft

## User
- id
- username
- password
- email

## ComputeResource
- id
- name
- type
- description
- enabled

## ResourceSlot
- id
- resourceId
- startTime
- endTime
- status

## Reservation
- id
- userId
- slotId
- status
- requestId
- createdAt
- updatedAt

## Relationship

User -> Reservation -> ResourceSlot -> ComputeResource
