# People commands

## call

Call a contact by name or dial a phone number. With phone access granted, the call starts directly.

```sh
call 'Alex Kim'
```

Replace the name with one of your contacts, or use a phone number. Phone permission is required. Calling by name also requires contacts permission.

If a name matches several contacts, use the phone number you want to call. Look it up with `contacts` first.

## contacts

Find contacts by name or phone number. Results include the name, number, and contact ID.

```sh
contacts
contacts Alex
```

With no query, `contacts` lists contacts with phone numbers. `contacts list` is equivalent. Results are limited to 200 entries. Reading contacts requires contacts permission.

To add a contact, open Android's contact editor:

```sh
contacts add
```

To edit one, replace `ID` with a contact ID from the results:

```sh
contacts edit ID
```
