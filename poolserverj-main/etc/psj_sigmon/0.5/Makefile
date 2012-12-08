CC?=gcc
CFLAGS=-O2 -Wall -pipe
LDFLAGS=

SOURCES=psj_sigmon.c
OBJECTS=$(SOURCES:.c=.o)

all: $(OBJECTS) psj_sigmon

psj_sigmon: $(OBJECTS)
	$(CC) $(LDFLAGS) $(OBJECTS) -o $@

clean:
	rm -f psj_sigmon $(OBJECTS)

.c.o:
	$(CC) $(CFLAGS) -c $<
