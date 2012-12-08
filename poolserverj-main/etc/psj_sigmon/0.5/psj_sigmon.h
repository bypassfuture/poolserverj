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

#ifndef false
# define false 0
#endif
#ifndef true
# define true !false
#endif

struct psj {
	char *hostport;

	struct addrinfo *ai;
};

struct bitcoind {
	char *name;
	uint32_t pid;
	char *pidfile;
	time_t pidfile_mtime;
};

#define PSJ_ARR_SIZE 50
#define BITCOIND_ARR_SIZE 50
struct config {
	struct psj *psj_list[PSJ_ARR_SIZE];
	uint32_t psj_used;
	struct bitcoind *bitcoind_list[BITCOIND_ARR_SIZE];
	uint32_t bitcoind_used;

	char *pidfile;

	char *ident;

	pid_t force_pid;

	int32_t daemon;
	int32_t daemon_done;

};
