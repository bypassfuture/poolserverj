/*
Copyright (c) 2011, Chris Elsworth <chris@shagged.org>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL CHRIS ELSWORTH BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/


#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include <syslog.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <limits.h>
#include <getopt.h>

extern char *optarg;
extern int optind, opterr, optopt;

#include "psj_sigmon.h"

#define APPLOG(pri, fmt, ...) \
	_applog(__FILE__, __LINE__, pri, fmt, ##__VA_ARGS__)

int32_t sig, pid;
static void sig_handler(int32_t signum, siginfo_t *si, void *context)
{
	sig = signum;
	pid = si->si_pid;

	return;
}

void _applog(const char *file, uint32_t line, int32_t priority, char *fmt, ...)
{
	va_list ap;

	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	fprintf(stderr, "\n");
	va_end(ap);
}

struct addrinfo *parse_hostport(char *hostport)
{
	int32_t s;
	char *p, *host, *port;
	struct addrinfo hints, *result;

	/* hostport is addr:port - this can be an IP (4 or 6) or address
	 * work backwards from end and find first : */
	p = strrchr(hostport, ':');

	if (p == NULL)
	{
		APPLOG(LOG_WARNING, "no : found in %s", hostport);
		return NULL;
	}
	*p = '\0';

	host = hostport;
	port = p + 1;

	memset(&hints, 0, sizeof(struct addrinfo));
	hints.ai_family = AF_UNSPEC; /* Allow IPv4 or IPv6 */
	hints.ai_socktype = SOCK_STREAM;
	hints.ai_flags = 0;
	hints.ai_protocol = 0;

	s = getaddrinfo(host, port, &hints, &result);
	if (s != 0)
	{
		APPLOG(LOG_WARNING, "getaddrinfo: %s\n", gai_strerror(s));
		return NULL;
	}


	return result;
}

struct psj *parse_psj(char *buf)
{
	char *p;
	struct psj *r;

	r = malloc(sizeof(*r));
	if (r == NULL)
	{
		APPLOG(LOG_CRIT, "malloc failed");
		exit(255);
	}

	p = strtok(buf, ",");
	if (!p)
	{
		APPLOG(LOG_ERR, "missing psj host:port");
		free(r);
		return NULL;
	}
	
	r->hostport = strdup(p);
	r->ai = parse_hostport(p);
	if (r->ai == NULL)
	{
		free(r->hostport);
		free(r);
		return NULL;
	}

	return r;
}

struct bitcoind *parse_bitcoind(char *buf)
{
	char *p;
	struct bitcoind *r;

	r = malloc(sizeof(*r));
	if (r == NULL)
	{
		APPLOG(LOG_CRIT, "malloc failed");
		exit(255);
	}

	p = strtok(buf, ",");
	if (!p)
	{
		APPLOG(LOG_ERR, "missing bitcoind name");
		free(r);
		return NULL;
	}
	r->name = strdup(p);

	p = strtok(NULL, ",");
	if (!p)
	{
		APPLOG(LOG_ERR, "missing bitcoind pidfile");
		free(r->name);
		free(r);
		return NULL;
	}
	r->pidfile = strdup(p);

	r->pidfile_mtime = 0;

	return r;
}

#define CFG_BUFSIZE 256
int32_t parse_cfg(const char *cfgfile, struct config *cfg)
{
	FILE *fd;
	char *p, buf[CFG_BUFSIZE];
	uint32_t lineno;

	cfg->psj_used = 0;
	cfg->bitcoind_used = 0;
	cfg->pidfile = NULL;
	cfg->ident = NULL;
	cfg->daemon = false;
	cfg->daemon_done = false;

	fd = fopen(cfgfile, "r");
	if (fd == NULL)
	{
		APPLOG(LOG_CRIT, "fopen cfgfile failed");
		return -1;
	}

	lineno = 0;
	while(fgets(buf, CFG_BUFSIZE, fd))
	{
		lineno++;

		if (buf[0] == ';' || buf[0] == '\r' || buf[0] == '\n')
			/* comment or empty line*/
			continue;

		/* nuke CRLF */
		if ((p = strchr(buf, '\r'))) *p = '\0';
		if ((p = strchr(buf, '\n'))) *p = '\0';

		if (!strncmp(buf, "ident", 5))
		{
			p = buf+6;
			cfg->ident = strdup(p);
		}

		if (!strncmp(buf, "daemon", 6))
			cfg->daemon = true;

		if (!strncmp(buf, "pidfile", 7))
		{
			if (cfg->pidfile)
			{
				APPLOG(LOG_WARNING, "pidfile already defined, ignoring duplicate at line %u of %s", lineno, cfgfile);
				continue;
			}
			p = buf+8;
			cfg->pidfile = strdup(p);
		}

		if (!strncmp(buf, "bitcoind", 8))
		{
			struct bitcoind *r;

			if (cfg->bitcoind_used == BITCOIND_ARR_SIZE)
			{
				APPLOG(LOG_CRIT, "no more room in bitcoind config array, ignoring line %u of %s", lineno, cfgfile);
				continue;
			}

			r = parse_bitcoind(buf+9);
			if (!r)
			{
				APPLOG(LOG_WARNING, "error parsing bitcoind at line %u of %s", lineno, cfgfile);
				continue;
			}

			cfg->bitcoind_list[cfg->bitcoind_used++] = r;
		}

		if (!strncmp(buf, "psj", 3))
		{
			struct psj *r;

			if (cfg->psj_used == PSJ_ARR_SIZE)
			{
				APPLOG(LOG_CRIT, "no more room in psj config array, ignoring line %u of %s", lineno, cfgfile);
				continue;
			}

			r = parse_psj(buf+4);

			if (!r)
			{
				APPLOG(LOG_WARNING, "error parsing psj at line %u of %s", lineno, cfgfile);
				continue;
			}

			cfg->psj_list[cfg->psj_used++] = r;
		}
	}
	fclose(fd);

	return 0;
}

void poke_psj(struct config *cfg, struct psj *psj, struct bitcoind *bitcoind)
{
	int32_t len, fd = -1;
	struct addrinfo *rp;
	char sendbuf[128];

	for(rp = psj->ai ; rp != NULL ; rp = rp->ai_next)
	{
		fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
		if (fd == -1)
			continue;

		if (connect(fd, rp->ai_addr, rp->ai_addrlen) != -1)
			break;

		close(fd);
	}

	if (rp == NULL)
	{
		APPLOG(LOG_ERR, "can't connect to %s: %s", psj->hostport, strerror(errno));
		return;
	}

	len = sprintf(sendbuf, "%s:%s\n",
		cfg->ident ? cfg->ident : "psj_sigmon",
		bitcoind->name);
	send(fd, sendbuf, len, 0);

	close(fd);
}

uint32_t read_pidfile(const char *pidfile)
{
	FILE *fd;
	uint32_t pid;

	fd = fopen(pidfile, "r");
	if (fd == NULL)
	{
		APPLOG(LOG_WARNING, "%s on open pidfile %s", strerror(errno), pidfile);
		return 0;
	}

	if (fscanf(fd, "%u", &pid) != 1)
	{
		APPLOG(LOG_WARNING, "error reading pid from %s", pidfile);
	}

	fclose(fd);

	return pid;
}

int32_t write_pidfile(const char *pidfile)
{
	pid_t pid;
	int32_t fd, len;
	char buf[16], tmp_pidfile[PATH_MAX];

	pid = getpid();

	if (snprintf(tmp_pidfile, PATH_MAX, "%s.%u", pidfile, pid) >= PATH_MAX)
	{
		APPLOG(LOG_ERR, "pidfile path is too long");
		return -1;
	}

	fd = open(tmp_pidfile, O_WRONLY | O_CREAT | O_EXCL, (mode_t)0600);
	if (fd == -1)
	{
		APPLOG(LOG_CRIT, "open pidfile failed");
		return -1;
	}
	len = snprintf(buf, sizeof(buf), "%u", pid);
	if (write(fd, buf, len) != len)
	{
		APPLOG(LOG_ERR, "write to tmp pidfile failed: %s", strerror(errno));
		unlink(tmp_pidfile);
		close(fd);
		return -1;
	}
	close(fd);

	if (link(tmp_pidfile, pidfile))
	{
		APPLOG(LOG_CRIT, "can't make %s, psj_sigmon already running?", pidfile);
		unlink(tmp_pidfile);
		return -1;
	}

	unlink(tmp_pidfile);

	return 0;
}

void usage()
{
	fprintf(stderr, "Usage: psj_sigmon [args]\n"
		"    -c file    read config file file [default: config.cfg]\n"
		"    -s pid     assume every signal comes from pid\n"
		"    -h         help\n"
		"\n");

	exit(EXIT_SUCCESS);
}

int32_t main(int argc, char *argv[])
{
	int32_t opt;
	uint32_t n;
	char *cfgfile = NULL;
	struct stat sb;
	struct bitcoind *b, *sigfrom;
	struct psj *psj;
	struct sigaction act;
	struct config *cfg;

	cfg = malloc(sizeof(*cfg));
	if (cfg == NULL)
	{
		APPLOG(LOG_CRIT, "cfg malloc failed");
		exit(255);
	}

	while ((opt = getopt(argc, argv, "hs:c:")) != -1)
	{
		switch(opt)
		{
			case 'c':
				cfgfile = optarg;
				break;
			case 's':
				cfg->force_pid = atoi(optarg);
				break;

			default:
			case 'h':
				usage();
		}
	}

	APPLOG(LOG_NOTICE, "psj_sigmon v0.5 starting up");

	/* config file parsing */
	if (parse_cfg(cfgfile ? cfgfile : "config.cfg", cfg))
	{
		APPLOG(LOG_CRIT, "parse_cfg error");
		exit(255);
	}

	if (cfg->bitcoind_used == 0)
	{
		APPLOG(LOG_CRIT, "no bitcoind defined, exiting");
		exit(EXIT_SUCCESS);
	}
	if (cfg->psj_used == 0)
	{
		APPLOG(LOG_CRIT, "no psj defined, exiting");
		exit(EXIT_SUCCESS);
	}

	if (cfg->pidfile)
	{
		if (write_pidfile(cfg->pidfile) != 0)
		{
			APPLOG(LOG_CRIT, "write_pidfile failed");
			exit(255);
		}
	}

	if (cfg->daemon)
	{
		APPLOG(LOG_NOTICE, "daemonising, you will hear no more from me");
		if (daemon(false, false) == -1)
		{
			APPLOG(LOG_CRIT, "except daemonising failed..  dying");
			exit(255);
		}
		cfg->daemon_done = true;
	}

	/* install signal handlers */
	act.sa_flags = SA_SIGINFO;
	act.sa_sigaction = &sig_handler;
	sigaction(SIGUSR1, &act, NULL);
	sigaction(SIGINT, &act, NULL);
	sigaction(SIGTERM, &act, NULL);


	while(1)
	{
		select(0, NULL, NULL, NULL, NULL);

		APPLOG(LOG_INFO, "got signal %d, from pid %d", sig, pid);

		/* handle INT and TERM */
		if (sig == SIGINT || sig == SIGTERM)
			/* break out of loop and exit gracefully */
			break;

		/* ignore non-USR1 */
		if (sig != SIGUSR1)
			continue;

		if (cfg->force_pid)
		{
			pid = cfg->force_pid;
			APPLOG(LOG_DEBUG, "forcing sending-pid to %d", pid);
		}

		sigfrom = NULL;
		for(n = 0 ; n < cfg->bitcoind_used ; n++)
		{
			/* check if pid == this bitcoind's pid */
			b = cfg->bitcoind_list[n];
			
			if (stat(b->pidfile, &sb) == -1)
			{
				APPLOG(LOG_WARNING, "%s on stat of %s", strerror(errno), b->pidfile);
				continue;
			}

			if (sb.st_mtime > b->pidfile_mtime)
			{
				/* refresh what we think the pid is of this
				 * bitcoind */
				b->pid = read_pidfile(b->pidfile);
				b->pidfile_mtime = sb.st_mtime;
				APPLOG(LOG_DEBUG, "%s changed: new pid is %u", b->pidfile, b->pid);
			}

			if (b->pid == pid)
			{
				APPLOG(LOG_DEBUG, "signal matches %s (%s)", b->pidfile, b->name);
				sigfrom = b;
				break;
			}
		}

		if (sigfrom == NULL)
		{
			APPLOG(LOG_WARNING, "signal not from a recognised pid");
			continue;
		}

		/* tell each psj about b->name */
		for(n = 0 ; n < cfg->psj_used ; n++)
		{
			psj = cfg->psj_list[n];
			APPLOG(LOG_INFO, "notifying %s of signal", psj->hostport);
			poke_psj(cfg,psj, b);
		}
	}

	APPLOG(LOG_INFO, "shutting down, removing pidfile");
	unlink(cfg->pidfile);

	/* we're lazy, the kernel will clean up our memory, sorry valgrind :p */

	return EXIT_SUCCESS;
}
